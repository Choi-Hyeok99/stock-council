#!/usr/bin/env python3
"""Probe timestamped market data on KIS demo REST; no account/order access."""

import importlib.util
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

SEOUL = ZoneInfo("Asia/Seoul")
MAX_AGE_SECONDS = 120  # Initial diagnostic limit, not a strategy performance claim.
STOCK_CODE = "005930"  # Connectivity probe only; never changes the watchlist.


@dataclass(frozen=True)
class TimedObservation:
    trade_at: datetime
    trade_price: Decimal
    bar_at: datetime
    bar_price: Decimal
    received_at: datetime


def validate_observation(trades: list[dict], bars: list[dict], received_at: datetime) -> TimedObservation:
    if not trades or not bars:
        raise ValueError("missing trade/minute-bar data")
    latest_bar = max(bars, key=lambda row: (row.get("stck_bsop_date", ""), row.get("stck_cntg_hour", "")))
    latest_trade = max(trades, key=lambda row: row.get("stck_cntg_hour", ""))
    try:
        # Trade REST exposes time only. Confirm the session using a dated minute bar
        # from the same symbol; never silently substitute today's date.
        date = latest_bar["stck_bsop_date"]
        bar_at = datetime.strptime(date + latest_bar["stck_cntg_hour"], "%Y%m%d%H%M%S").replace(tzinfo=SEOUL)
        trade_at = datetime.strptime(date + latest_trade["stck_cntg_hour"], "%Y%m%d%H%M%S").replace(tzinfo=SEOUL)
        bar_price = Decimal(latest_bar["stck_prpr"])
        trade_price = Decimal(latest_trade["stck_prpr"])
    except (KeyError, ValueError, TypeError, InvalidOperation):
        raise ValueError("invalid date/time/price fields") from None
    if not all(price.is_finite() and price > 0 for price in (bar_price, trade_price)):
        raise ValueError("nonpositive/nonfinite price")
    local_now = received_at.astimezone(SEOUL)
    if bar_at.date() != local_now.date():
        raise ValueError("minute-bar session is not today")
    if not ("090000" <= local_now.strftime("%H%M%S") < "153000"):
        raise ValueError("outside the regular market observation window")
    for observed in (bar_at, trade_at):
        age = (received_at - observed).total_seconds()
        if age < 0:
            raise ValueError("future provider timestamp")
        if age > MAX_AGE_SECONDS:
            raise ValueError("stale provider timestamp")
    return TimedObservation(trade_at, trade_price, bar_at, bar_price, received_at)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    spec = importlib.util.spec_from_file_location("kis_readonly", root / "scripts/check-kis-readonly.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    path = root / ".env.kis"
    if not path.is_file():
        print("KIS credentials are not configured.")
        return 2
    credentials = helper.read_credentials(path)
    app_key = credentials.get("KIS_APP_KEY", "")
    app_secret = credentials.get("KIS_APP_SECRET", "")
    if not app_key or not app_secret:
        print("KIS credentials are incomplete.")
        return 2
    token_result = {}
    try:
        status, token_result = helper.request_json("POST", helper.BASE_URL + "/oauth2/tokenP",
            {"Content-Type": "application/json; charset=utf-8"},
            {"grant_type": "client_credentials", "appkey": app_key, "appsecret": app_secret})
        token = token_result.get("access_token")
        if status != 200 or not token:
            raise ValueError("token request rejected")
        headers = {"Content-Type": "application/json; charset=utf-8", "authorization": f"Bearer {token}",
                   "appkey": app_key, "appsecret": app_secret, "custtype": "P"}

        def query(endpoint: str, tr_id: str, params: dict) -> dict:
            status, result = helper.request_json("GET",
                helper.BASE_URL + "/uapi/domestic-stock/v1/quotations/" + endpoint + "?" + urlencode(params),
                dict(headers, tr_id=tr_id))
            if status != 200 or result.get("rt_cd") != "0":
                raise ValueError("market-data request rejected")
            return result

        params = {"FID_COND_MRKT_DIV_CODE": "J", "FID_INPUT_ISCD": STOCK_CODE}
        trades = query("inquire-ccnl", "FHKST01010300", params).get("output") or []
        time.sleep(1)  # Demo API call pacing; no parallel quote requests.
        bars = query("inquire-time-itemchartprice", "FHKST03010200", dict(params,
            FID_INPUT_HOUR_1=datetime.now(SEOUL).strftime("%H%M%S"),
            FID_PW_DATA_INCU_YN="Y", FID_ETC_CLS_CODE="")).get("output2") or []
        observation = validate_observation(trades, bars, datetime.now(SEOUL))
        print("KIS demo timestamped REST market-data probe passed.")
        print(f"Probe {STOCK_CODE}: trade price {observation.trade_price}, trade time {observation.trade_at.isoformat(timespec='seconds')} (date confirmed by minute-bar session).")
        print(f"Minute bar: {observation.bar_at.isoformat(timespec='seconds')}, price {observation.bar_price}; the current bar can still be forming.")
        print(f"Receipt: {observation.received_at.isoformat(timespec='seconds')}; trade age {(observation.received_at-observation.trade_at).total_seconds():.1f}s.")
        print("Diagnostic only: app data provider, watchlist and paper ledger were not changed.")
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
        # Only controlled validation messages; do not print raw transport/provider responses.
        detail = str(error) if isinstance(error, ValueError) else "network/response handling failure"
        print(f"Timed quote probe did not pass: {detail}.")
        return 1
    finally:
        token_result.clear()
        credentials.clear()


if __name__ == "__main__":
    sys.exit(main())
