package com.medremind.app

import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.data.DoseGroup
import com.medremind.app.data.DoseItem
import com.medremind.app.data.DrugMatcher
import com.medremind.app.data.DrugProduct
import com.medremind.app.data.FoodRule
import com.medremind.app.data.Frequency
import com.medremind.app.data.GroupWithItems
import com.medremind.app.data.Medicine
import com.medremind.app.data.MedicineWithItems
import com.medremind.app.data.Quantities
import com.medremind.app.data.SlotKind
import com.medremind.app.data.SlotMedicine
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleLogicTest {

    private val zone = ZoneId.of("Asia/Dhaka")

    private fun item(frequency: Frequency, dayOfWeek: Int? = null, dayOfMonth: Int? = null, quantity: Double = 1.0) =
        DoseItem(
            groupId = 1,
            medicineId = 1,
            quantity = quantity,
            foodRule = FoodRule.AFTER_FOOD,
            frequency = frequency,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
        )

    private fun slot(vararg items: DoseItem, hour: Int? = 9) = GroupWithItems(
        group = DoseGroup(id = 1, label = "সকাল", hour = hour, minute = 0),
        items = items.map { SlotMedicine(it, Medicine(id = it.medicineId, name = "X")) },
    )

    private fun millis(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun weeklyItemIsDueOnlyOnItsWeekday() {
        val friday = item(Frequency.WEEKLY, dayOfWeek = 5)
        assertTrue(friday.isDueOn(LocalDate.of(2026, 10, 9)))
        assertFalse(friday.isDueOn(LocalDate.of(2026, 10, 8)))
    }

    @Test
    fun monthlyDayPastMonthEndFallsOnLastDay() {
        val on31st = item(Frequency.MONTHLY, dayOfMonth = 31)
        assertTrue(on31st.isDueOn(LocalDate.of(2026, 2, 28)))
        assertFalse(on31st.isDueOn(LocalDate.of(2026, 3, 30)))
        assertTrue(on31st.isDueOn(LocalDate.of(2026, 3, 31)))
    }

    @Test
    fun weeklySlotSkipsToItsNextWeekday() {
        // Thursday 8 Oct 2026, 10:00 — the Friday-only slot next rings Friday 9:00.
        val from = millis(LocalDate.of(2026, 10, 8), 10)
        val next = AlarmScheduler.nextTrigger(slot(item(Frequency.WEEKLY, dayOfWeek = 5)), from, zone)
        assertEquals(millis(LocalDate.of(2026, 10, 9), 9), next)
    }

    @Test
    fun dailySlotPastTodaysTimeRingsTomorrow() {
        val from = millis(LocalDate.of(2026, 10, 8), 10)
        val next = AlarmScheduler.nextTrigger(slot(item(Frequency.DAILY)), from, zone)
        assertEquals(millis(LocalDate.of(2026, 10, 9), 9), next)
    }

    @Test
    fun emptyOrUntimedSlotsNeverRing() {
        val from = millis(LocalDate.of(2026, 10, 8), 10)
        assertNull(AlarmScheduler.nextTrigger(slot(), from, zone))
        assertNull(AlarmScheduler.nextTrigger(slot(item(Frequency.DAILY), hour = null), from, zone))
    }

    @Test
    fun daysLeftUsesEveryScheduledDose() {
        val medicine = MedicineWithItems(
            medicine = Medicine(id = 1, name = "Janmet 50/500", stock = 20.0),
            items = listOf(item(Frequency.DAILY), item(Frequency.DAILY, quantity = 0.5)),
        )
        assertEquals(13, medicine.daysLeft)
        assertFalse(medicine.isLowOnStock)
        assertTrue(medicine.copy(medicine = medicine.medicine.copy(stock = 6.0)).isLowOnStock)
        assertNull(medicine.copy(medicine = medicine.medicine.copy(stock = null)).daysLeft)
    }

    @Test
    fun quantitiesReadNaturally() {
        assertEquals("½", Quantities.bengali(0.5))
        assertEquals("১", Quantities.bengali(1.0))
        assertEquals("১½", Quantities.bengali(1.5))
        assertEquals("Empa 10", Quantities.describe("Empa 10", 1.0))
        assertEquals("Janmet 50/500 (½টি)", Quantities.describe("Janmet 50/500", 0.5))
    }

    @Test
    fun slotKindsCoverTheirHours() {
        assertTrue(SlotKind.NOON.covers(14))
        assertTrue(SlotKind.NIGHT.covers(22))
        assertTrue(SlotKind.NIGHT.covers(1))
        assertFalse(SlotKind.MORNING.covers(19))
    }

    private val products = listOf(
        DrugProduct("Janmet", "50 mg+500 mg", "Sitagliptin + Metformin Hydrochloride", "Tablet"),
        DrugProduct("Janmet", "50 mg+1000 mg", "Sitagliptin + Metformin Hydrochloride", "Tablet"),
        DrugProduct("Danamet", "100 mg", "Danazol", "Capsule"),
        DrugProduct("Rolip", "10 mg", "Rosuvastatin", "Tablet"),
        DrugProduct("D-Rise", "40000 IU", "Colecalciferol [Vitamin D3]", "Capsule"),
    )

    @Test
    fun misreadBrandIsSuggestedWithMatchingStrength() {
        val top = DrugMatcher(products).suggest("Janner 50/500", form = "tablet").first()
        assertEquals("Janmet 50/500", top.product.displayName)
        assertFalse(top.exact)
    }

    @Test
    fun exactBrandIsMarkedExact() {
        val top = DrugMatcher(products).suggest("Tab. Rolip 10").first()
        assertTrue(top.exact)
        assertEquals("Rosuvastatin", top.product.generic)
    }

    @Test
    fun unrelatedNamesGetNoSuggestion() {
        assertTrue(DrugMatcher(products).suggest("Paracetamol").isEmpty())
    }

    @Test
    fun brandsWithHyphensAndUnitsSplitCorrectly() {
        assertEquals("D-Rise" to "40000", DrugMatcher.split("D-Rise 40000"))
        assertEquals("D-Rise 40000 IU", products.last().displayName)
        assertEquals(listOf("Janmet 50/500", "Janmet 50/1000"), DrugMatcher(products).complete("jan").map { it.displayName })
    }
}
