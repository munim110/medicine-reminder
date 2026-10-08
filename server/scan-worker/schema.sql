-- Scan counts per user per day (Asia/Dhaka date). uid '*' holds the all-users total.
CREATE TABLE IF NOT EXISTS usage (
  uid   TEXT    NOT NULL,
  day   TEXT    NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, day)
);
