import datetime
import zoneinfo

from app.core import clock

IST = zoneinfo.ZoneInfo("Asia/Kolkata")


def test_day_start_is_local_midnight():
    at = datetime.datetime(2024, 3, 15, 14, 30, tzinfo=IST).timestamp()
    start = clock.day_start(at=at, tz_name="Asia/Kolkata")
    expected = datetime.datetime(2024, 3, 15, 0, 0, tzinfo=IST).timestamp()
    assert start == expected


def test_meal_just_after_midnight_counts_toward_that_day():
    # a log at 00:05 IST and "now" at 00:32 IST fall in the same IST calendar day
    logged_at = datetime.datetime(2024, 3, 15, 0, 5, tzinfo=IST).timestamp()
    now_at = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=IST).timestamp()
    assert clock.day_start(at=logged_at) == clock.day_start(at=now_at)
    assert logged_at >= clock.day_start(at=now_at)


def test_log_from_previous_evening_does_not_count_toward_next_day():
    logged_at = datetime.datetime(2024, 3, 14, 21, 0, tzinfo=IST).timestamp()
    now_at = datetime.datetime(2024, 3, 15, 0, 32, tzinfo=IST).timestamp()
    assert logged_at < clock.day_start(at=now_at)


def test_local_hour_is_fractional():
    at = datetime.datetime(2024, 3, 15, 13, 30, tzinfo=IST).timestamp()
    assert clock.local_hour(at, tz_name="Asia/Kolkata") == 13.5
