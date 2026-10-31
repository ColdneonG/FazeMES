"""Exercise-date boundaries must not create future completed production."""
from datetime import datetime, timedelta
import unittest

from testdata_dashboard import scenario_times


class DashboardFixtureTimesTest(unittest.TestCase):
    def test_today_never_places_completed_feedback_in_the_future(self):
        for hour, minute in ((0, 0), (0, 5), (6, 15), (7, 10), (8, 0), (23, 59)):
            now = datetime(2026, 10, 31, hour, minute)
            with self.subTest(now=now):
                start, reported = scenario_times(now, now)
                self.assertEqual(now.date(), start.date())
                self.assertEqual(now.date(), reported.date())
                self.assertLessEqual(start, reported)
                self.assertLessEqual(reported, now)

    def test_historical_business_dates_stay_on_the_explicit_day(self):
        now = datetime(2026, 10, 31, 0, 5)
        for age in range(1, 7):
            day = now - timedelta(days=age)
            start, reported = scenario_times(now, day)
            self.assertEqual((7, 0), (start.hour, start.minute))
            self.assertEqual((7, 30), (reported.hour, reported.minute))
            self.assertEqual(day.date(), reported.date())
            self.assertLess(reported, now)


if __name__ == '__main__':
    unittest.main()
