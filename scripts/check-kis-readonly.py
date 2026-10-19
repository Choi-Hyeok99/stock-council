#!/usr/bin/env python3
"""Make one sanitized, read-only KIS mock quote request using .env.kis."""

import json
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
from urllib.parse import urlencode


BASE_URL = "https://openapivts.koreainvestment.com:29443"
STOCK_CODE = "005930"  # Connectivity probe only; not added to the trading watchlist.


def read_credentials(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def curl_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "")
    return f'"{escaped}"'


def request_json(method: str, url: str, headers: dict[str, str], body: dict | None = None) -> tuple[int, dict]:
    # Pass credentials and tokens to curl over stdin rather than exposing them in argv.
    config = [
        "silent",
        "show-error",
        f"url = {curl_quote(url)}",
        f"request = {curl_quote(method)}",
        'write-out = "\\n__HTTP_CODE__%{http_code}"',
    ]
    config.extend(f"header = {curl_quote(f'{name}: {value}') }" for name, value in headers.items())
    if body is not None:
        config.append(f"data-binary = {curl_quote(json.dumps(body, separators=(',', ':')))}")

    result = subprocess.run(
        ["curl", "--config", "-"],
        input="\n".join(config) + "\n",
        text=True,
        capture_output=True,
        timeout=20,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("curl request failed")
    try:
        response_body, status_text = result.stdout.rsplit("\n__HTTP_CODE__", 1)
        status_code = int(status_text.strip())
        payload = json.loads(response_body)
    except (ValueError, json.JSONDecodeError):
        raise RuntimeError("KIS returned an unreadable response") from None
    return status_code, payload


def main() -> int:
    project_root = Path(__file__).resolve().parent.parent
    credentials_path = project_root / ".env.kis"
    if not credentials_path.is_file():
        print("KIS credentials are not configured. Run scripts/setup-kis-credentials.sh first.")
        return 2

    credentials = read_credentials(credentials_path)
    app_key = credentials.get("KIS_APP_KEY", "")
    app_secret = credentials.get("KIS_APP_SECRET", "")
    if not app_key or not app_secret:
        print("KIS credentials are incomplete. Re-run scripts/setup-kis-credentials.sh.")
        return 2

    try:
        token_status, token_result = request_json(
            "POST",
            f"{BASE_URL}/oauth2/tokenP",
            {"Content-Type": "application/json; charset=utf-8"},
            {
            "grant_type": "client_credentials",
            "appkey": app_key,
            "appsecret": app_secret,
            },
        )
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        print("KIS mock token request failed due to local network/response handling. Secret values were not displayed.")
        return 1

    access_token = token_result.get("access_token")
    if token_status != 200 or not access_token:
        code = token_result.get("error_code") or token_result.get("msg_cd") or "unknown"
        print(f"KIS mock token was not issued (HTTP {token_status}, provider code {code}). Check the KIS application status.")
        return 1

    query = urlencode({
        "FID_COND_MRKT_DIV_CODE": "J",
        "FID_INPUT_ISCD": STOCK_CODE,
    })
    try:
        quote_status, quote_result = request_json(
            "GET",
            f"{BASE_URL}/uapi/domestic-stock/v1/quotations/inquire-price?{query}",
            {
            "Content-Type": "application/json; charset=utf-8",
            "authorization": f"Bearer {access_token}",
            "appKey": app_key,
            "appSecret": app_secret,
            "tr_id": "FHKST01010100",
            },
        )
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        print("KIS mock quote request failed due to local network/response handling. The token was not saved or displayed.")
        return 1
    finally:
        access_token = None

    if quote_status != 200 or quote_result.get("rt_cd") != "0":
        code = quote_result.get("msg_cd") or "unknown"
        print(f"KIS quote API returned an error (HTTP {quote_status}, code: {code}). No raw response was printed.")
        return 1

    quote = quote_result.get("output") or {}
    price = quote.get("stck_prpr")
    name = quote.get("hts_kor_isnm") or "조회 종목"
    if not price:
        print("KIS quote response had no current price. No raw response was printed.")
        return 1

    time_hint = quote.get("stck_cntg_hour") or quote.get("aspr_acpt_hour") or "응답 시각 미제공"

    # Daily bars include an exchange session date; they do not substitute for an intraday quote timestamp.
    now_seoul = datetime.now(ZoneInfo("Asia/Seoul"))
    query = urlencode({
        "FID_COND_MRKT_DIV_CODE": "J",
        "FID_INPUT_ISCD": STOCK_CODE,
        "FID_INPUT_DATE_1": (now_seoul.date() - timedelta(days=30)).strftime("%Y%m%d"),
        "FID_INPUT_DATE_2": now_seoul.date().strftime("%Y%m%d"),
        "FID_PERIOD_DIV_CODE": "D",
        "FID_ORG_ADJ_PRC": "0",
    })
    try:
        bars_status, bars_result = request_json(
            "GET",
            f"{BASE_URL}/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice?{query}",
            {
                "Content-Type": "application/json; charset=utf-8",
                "authorization": f"Bearer {token_result.get('access_token')}",
                "appKey": app_key,
                "appSecret": app_secret,
                "tr_id": "FHKST03010100",
            },
        )
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        print("KIS daily-bar request failed; quote response was read-only and no raw data was printed.")
        return 1
    finally:
        token_result["access_token"] = None

    bars: list[dict] = []
    if bars_status == 200 and bars_result.get("rt_cd") == "0":
        bars = bars_result.get("output2") or []
    if not bars:
        code = bars_result.get("msg_cd") or "unknown"
        print(f"KIS daily-bar request returned no rows (HTTP {bars_status}, code: {code}). No raw response was printed.")
        return 1

    latest_bar = max(bars, key=lambda bar: str(bar.get("stck_bsop_date") or ""))
    latest_date = latest_bar.get("stck_bsop_date") or "기준일 미제공"
    latest_close = latest_bar.get("stck_clpr") or "종가 미제공"
    print("KIS mock-domain read-only quote request succeeded.")
    print(f"Probe only: {name} ({STOCK_CODE}), response price {price}, time field {time_hint}.")
    print(f"Daily-bar check: latest session date {latest_date}, close {latest_close}; checked at {now_seoul.isoformat(timespec='seconds')} KST.")
    print("This probe does not place an order or add the probe symbol to the app watchlist.")
    print("A daily session date is not an intraday quote timestamp. Mock-domain output and this check alone do not prove a live quote; do not use it for paper decisions until freshness is confirmed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
