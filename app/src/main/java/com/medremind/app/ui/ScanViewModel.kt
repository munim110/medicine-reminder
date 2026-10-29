package com.medremind.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.cloud.ScanClient
import com.medremind.app.cloud.ScanError
import com.medremind.app.cloud.ScanOutcome
import com.medremind.app.cloud.ScannedMedicine
import com.medremind.app.data.DrugDirectory
import com.medremind.app.data.DrugMatch
import com.medremind.app.data.FoodRule
import com.medremind.app.data.Frequency
import com.medremind.app.data.ImportEntry
import com.medremind.app.data.ScheduleRepository
import com.medremind.app.data.SlotKind
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DraftFrequency { DAILY, WEEKLY, MONTHLY, AS_NEEDED }

/** One medicine on the review screen: what the AI read, and what the user has made of it. */
data class Draft(
    val key: Int,
    /** Exactly what was read, shown so a misreading is visible next to the edited name. */
    val readAs: String,
    val name: String,
    val genericName: String? = null,
    val form: String? = null,
    val doseText: String? = null,
    val durationText: String? = null,
    val instructions: String? = null,
    val doses: Map<SlotKind, Double> = emptyMap(),
    val frequency: DraftFrequency = DraftFrequency.DAILY,
    val dayOfWeek: Int = LocalDate.now().dayOfWeek.value,
    val dayOfMonth: Int = LocalDate.now().dayOfMonth,
    val foodRule: FoodRule = FoodRule.ANY,
    val include: Boolean = true,
    /** The AI was unsure; the user must confirm against the paper before importing. */
    val needsCheck: Boolean = false,
    val checked: Boolean = false,
    val suggestions: List<DrugMatch> = emptyList(),
) {
    val hasTimes: Boolean get() = frequency == DraftFrequency.AS_NEEDED || doses.values.any { it > 0 }
    val ready: Boolean get() = !include || (name.isNotBlank() && hasTimes && (!needsCheck || checked))
}

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Working : ScanUiState
    data class Failed(val error: ScanError) : ScanUiState
    data class Review(
        val drafts: List<Draft>,
        val notes: String?,
        val scansLeft: Int,
        val notPrescription: Boolean,
        val saving: Boolean = false,
    ) : ScanUiState {
        val included: List<Draft> get() = drafts.filter { it.include }
        val canImport: Boolean get() = !saving && included.isNotEmpty() && drafts.all { it.ready }
    }
    data class Done(val added: Int, val newSlots: Int) : ScanUiState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val suggestionJobs = mutableMapOf<Int, Job>()

    /** The scan or import in flight; cancelled on close so a dismissed scan never reappears. */
    private var job: Job? = null

    /** Sends the photo off. [deleteAfter] is our own camera file, removed once read. */
    fun scan(photo: Uri, deleteAfter: File? = null) {
        if (_state.value == ScanUiState.Working) return
        _state.value = ScanUiState.Working
        job = viewModelScope.launch {
            val outcome = try {
                ScanClient.scan(getApplication(), photo)
            } finally {
                deleteAfter?.delete()
            }
            _state.value = when (outcome) {
                is ScanOutcome.Failure -> ScanUiState.Failed(outcome.reason)
                is ScanOutcome.Success -> {
                    val result = outcome.result
                    ScanUiState.Review(
                        drafts = result.medicines.mapIndexed { i, m -> m.toDraft(i) },
                        notes = result.notes,
                        scansLeft = result.scansLeftToday,
                        notPrescription = !result.isPrescription,
                    )
                }
            }
            (state.value as? ScanUiState.Review)?.drafts?.forEach { refreshSuggestions(it.key, it.name, it.form, 0) }
        }
    }

    fun edit(key: Int, change: (Draft) -> Draft) = _state.update { s ->
        if (s !is ScanUiState.Review) s else s.copy(drafts = s.drafts.map { if (it.key == key) change(it) else it })
    }

    fun rename(key: Int, name: String) {
        edit(key) { it.copy(name = name, genericName = null) }
        val form = (state.value as? ScanUiState.Review)?.drafts?.firstOrNull { it.key == key }?.form
        refreshSuggestions(key, name, form, 400)
    }

    fun pick(key: Int, match: DrugMatch) = edit(key) {
        it.copy(name = match.product.displayName, genericName = match.product.generic, suggestions = emptyList())
    }

    /** A medicine the AI missed entirely. */
    fun addBlank() = _state.update { s ->
        if (s !is ScanUiState.Review) s else {
            val key = (s.drafts.maxOfOrNull { it.key } ?: -1) + 1
            s.copy(drafts = s.drafts + Draft(key = key, readAs = "", name = "", foodRule = FoodRule.AFTER_FOOD))
        }
    }

    fun import() {
        val review = state.value as? ScanUiState.Review ?: return
        if (!review.canImport) return
        _state.value = review.copy(saving = true)
        // Not tied to close(): once saving starts it must finish, or the schedule is half-written.
        viewModelScope.launch {
            val entries = review.included.map { it.toEntry() }
            val created = try {
                ScheduleRepository.get(getApplication()).import(entries)
            } catch (e: Exception) {
                android.util.Log.e("ScanViewModel", "Import failed", e)
                _state.value = review.copy(saving = false)
                return@launch
            }
            AlarmScheduler.rescheduleAll(getApplication())
            if (_state.value != ScanUiState.Idle) _state.value = ScanUiState.Done(added = entries.size, newSlots = created)
        }
    }

    fun close() {
        job?.cancel()
        job = null
        suggestionJobs.values.forEach { it.cancel() }
        suggestionJobs.clear()
        _state.value = ScanUiState.Idle
    }

    private fun refreshSuggestions(key: Int, name: String, form: String?, debounceMillis: Long) {
        suggestionJobs[key]?.cancel()
        suggestionJobs[key] = viewModelScope.launch {
            delay(debounceMillis)
            // Fuzzy-matching every brand in the country is too slow for the main thread.
            val matches = runCatching {
                val directory = DrugDirectory.get(getApplication())
                withContext(Dispatchers.Default) { directory.suggest(name, form) }
            }.getOrDefault(emptyList())
            val exact = matches.firstOrNull { it.exact }
            edit(key) { draft ->
                if (draft.name != name) return@edit draft
                draft.copy(
                    // A brand spelled exactly right needs no suggestion, only its ingredient.
                    genericName = draft.genericName ?: exact?.product?.generic,
                    suggestions = if (exact != null) emptyList() else matches,
                )
            }
        }
    }
}

private fun ScannedMedicine.toDraft(key: Int): Draft {
    val fullName = listOfNotNull(name, strength).joinToString(" ")
    val parsed = listOf(SlotKind.MORNING to morning, SlotKind.NOON to noon, SlotKind.EVENING to evening, SlotKind.NIGHT to night)
        .mapNotNull { (kind, q) -> q?.takeIf { it > 0 }?.let { kind to it } }
        .toMap()
    return Draft(
        key = key,
        readAs = fullName,
        name = fullName,
        form = form,
        doseText = doseText,
        durationText = durationText,
        instructions = instructions,
        doses = parsed,
        frequency = when (frequency) {
            "weekly" -> DraftFrequency.WEEKLY
            "monthly" -> DraftFrequency.MONTHLY
            "as_needed" -> DraftFrequency.AS_NEEDED
            else -> DraftFrequency.DAILY
        },
        // Not stated on the paper: say so rather than guess before or after food.
        foodRule = foodRule ?: FoodRule.ANY,
        needsCheck = confidence != "high",
    )
}

private fun Draft.toEntry() = ImportEntry(
    name = name.trim(),
    genericName = genericName,
    form = form,
    instructions = listOfNotNull(instructions, durationText?.let { "মেয়াদ: $it" }).joinToString(" · ").ifBlank { null },
    foodRule = foodRule,
    doses = if (frequency == DraftFrequency.AS_NEEDED) emptyMap() else doses.filterValues { it > 0 },
    frequency = when (frequency) {
        DraftFrequency.WEEKLY -> Frequency.WEEKLY
        DraftFrequency.MONTHLY -> Frequency.MONTHLY
        else -> Frequency.DAILY
    },
    dayOfWeek = dayOfWeek,
    dayOfMonth = dayOfMonth,
)
