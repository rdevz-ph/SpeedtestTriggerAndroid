import json

from speedtest import SpeedtestLite, SpeedtestLiteError


def run_speedtest(no_download=False, no_upload=True):
    st = SpeedtestLite(secure=True)

    try:
        result = st.run_trigger_and_speedtest(
            delay_before_test=2,
            no_download=no_download,
            no_upload=no_upload,
            seconds=8,
            threads=4,
            download_max_megabytes=25,
            upload_max_megabytes=10,
        )
    except SpeedtestLiteError as exc:
        return json.dumps({"ok": False, "error": str(exc)})
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)})

    trigger = result["trigger"]
    mode = _mode_label(no_download, no_upload)

    if no_download and no_upload:
        download_text = "Skipped"
        upload_text = "Skipped"
        result_text = "Checker only"
    else:
        if result["download"] is not None:
            download = result["download"]
            download_text = (
                f"{download['download_mbps']} Mbps "
                f"({download['download_megabytes']} MB in {download['elapsed_seconds']}s)"
            )
        else:
            download_text = "Skipped"

        if result["upload"] is not None:
            upload = result["upload"]
            upload_text = (
                f"{upload['upload_mbps']} Mbps "
                f"({upload['upload_megabytes']} MB in {upload['elapsed_seconds']}s)"
            )
        else:
            upload_text = "Skipped"

        if result["download"] is not None and result["upload"] is not None:
            result_text = "Download + Upload completed"
        elif result["download"] is not None:
            result_text = "Download completed"
        elif result["upload"] is not None:
            result_text = "Upload completed"
        else:
            result_text = "Checker only"

    payload = {
        "ok": True,
        "isp": trigger.get("isp"),
        "ip": trigger.get("ip"),
        "server": trigger.get("server"),
        "sponsor": trigger.get("sponsor"),
        "ping": trigger.get("ping"),
        "distance_km": trigger.get("distance_km"),
        "server_id": trigger.get("server_id"),
        "mode": mode,
        "download_text": download_text,
        "upload_text": upload_text,
        "result_text": result_text,
    }
    return json.dumps(payload)


def _mode_label(no_download, no_upload):
    if no_download and no_upload:
        return "Checker mode"
    if no_download:
        return "Upload only"
    if no_upload:
        return "Download only"
    return "Download + Upload"