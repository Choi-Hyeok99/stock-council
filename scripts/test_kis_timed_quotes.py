"""Offline tests of timestamp rejection; never call KIS or alter paper data."""
import importlib.util
import sys
import unittest
from datetime import datetime
from pathlib import Path

spec = importlib.util.spec_from_file_location("timed_quotes", Path(__file__).with_name("check-kis-timed-quotes.py"))
timed = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = timed
spec.loader.exec_module(timed)


class TimedObservationTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 2, 14, 29, 58, tzinfo=timed.SEOUL)
        self.trades = [{"stck_cntg_hour": "142954", "stck_prpr": "275250"}]
        self.bars = [{"stck_bsop_date": "20261002", "stck_cntg_hour": "142900", "stck_prpr": "275250"}]

    def test_recent_dated_session_accepts(self):
        observation = timed.validate_observation(self.trades, self.bars, self.now)
        self.assertEqual((self.now-observation.trade_at).total_seconds(), 4)

    def test_missing_date_is_rejected(self):
        del self.bars[0]["stck_bsop_date"]
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, self.now)

    def test_old_session_is_rejected(self):
        self.bars[0]["stck_bsop_date"] = "20261001"
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, self.now)

    def test_stale_trade_is_rejected(self):
        self.trades[0]["stck_cntg_hour"] = "142700"
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, self.now)

    def test_future_trade_is_rejected(self):
        self.trades[0]["stck_cntg_hour"] = "143000"
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, self.now)

    def test_invalid_price_is_rejected(self):
        self.trades[0]["stck_prpr"] = "NaN"
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, self.now)

    def test_closed_market_is_rejected(self):
        now = datetime(2026, 10, 2, 16, 0, tzinfo=timed.SEOUL)
        with self.assertRaises(ValueError): timed.validate_observation(self.trades, self.bars, now)


if __name__ == "__main__": unittest.main()
