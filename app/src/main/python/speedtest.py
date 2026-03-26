#!/usr/bin/env python3
import argparse
import math
import threading
import time
import xml.etree.ElementTree as ET

import requests


class SpeedtestLiteError(Exception):
    pass


class SpeedtestLite:
    def __init__(self, secure=True, timeout=10):
        self.secure = secure
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/146.0.0.0 Safari/537.36"
                )
            }
        )

        self.client = {}
        self.servers = []
        self.best = {}
        self.ping = None

    def _scheme_url(self, url: str) -> str:
        if url.startswith("://"):
            return f'{"https" if self.secure else "http"}{url}'
        return url

    def _get(self, url: str, **kwargs):
        return self.session.get(self._scheme_url(url), timeout=self.timeout, **kwargs)

    def get_config(self):
        response = self._get("://www.speedtest.net/speedtest-config.php")
        response.raise_for_status()

        try:
            root = ET.fromstring(response.content)
            client = root.find("client")
            if client is None:
                raise SpeedtestLiteError("Missing client info in config response")

            self.client = {
                "ip": client.attrib.get("ip"),
                "isp": client.attrib.get("isp"),
                "lat": float(client.attrib.get("lat", 0)),
                "lon": float(client.attrib.get("lon", 0)),
            }
        except Exception as exc:
            raise SpeedtestLiteError(f"Failed to parse config: {exc}") from exc

    def get_servers(self):
        urls = [
            "://www.speedtest.net/speedtest-servers-static.php",
            "://www.speedtest.net/speedtest-servers.php",
            "http://c.speedtest.net/speedtest-servers-static.php",
            "http://c.speedtest.net/speedtest-servers.php",
        ]

        last_error = None

        for url in urls:
            try:
                response = self._get(url)
                response.raise_for_status()

                root = ET.fromstring(response.content)
                servers = []

                for elem in root.iter("server"):
                    attrib = elem.attrib
                    try:
                        servers.append(
                            {
                                "id": attrib.get("id"),
                                "name": attrib.get("name"),
                                "sponsor": attrib.get("sponsor"),
                                "country": attrib.get("country"),
                                "host": attrib.get("host"),
                                "url": attrib.get("url"),
                                "lat": float(attrib.get("lat", 0)),
                                "lon": float(attrib.get("lon", 0)),
                            }
                        )
                    except Exception:
                        continue

                if servers:
                    self.servers = servers
                    return

            except Exception as exc:
                last_error = exc

        raise SpeedtestLiteError(f"Failed to retrieve server list: {last_error}")

    @staticmethod
    def _distance_km(lat1, lon1, lat2, lon2):
        radius = 6371.0
        dlat = math.radians(lat2 - lat1)
        dlon = math.radians(lon2 - lon1)

        a = (
            math.sin(dlat / 2) ** 2
            + math.cos(math.radians(lat1))
            * math.cos(math.radians(lat2))
            * math.sin(dlon / 2) ** 2
        )
        c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
        return radius * c

    def get_closest_servers(self, limit=20):
        if not self.client:
            self.get_config()
        if not self.servers:
            self.get_servers()

        lat1 = self.client["lat"]
        lon1 = self.client["lon"]

        ranked = []
        for server in self.servers:
            try:
                d = self._distance_km(lat1, lon1, server["lat"], server["lon"])
                server_copy = dict(server)
                server_copy["d"] = d
                ranked.append(server_copy)
            except Exception:
                continue

        ranked.sort(key=lambda s: s["d"])
        return ranked[:limit]

    def _latency_url(self, server, attempt=None):
        base = server["url"].rsplit("/", 1)[0]
        stamp = int(time.time() * 1000)
        if attempt is None:
            return f"{base}/latency.txt?x={stamp}"
        return f"{base}/latency.txt?x={stamp}&attempt={attempt}"

    def _measure_server_latency(self, server, attempts=3):
        values = []

        for i in range(attempts):
            url = self._latency_url(server, i)
            try:
                start = time.perf_counter()
                response = self.session.get(url, timeout=3)
                elapsed_ms = (time.perf_counter() - start) * 1000

                if response.status_code == 200 and response.text.strip().startswith(
                    "test=test"
                ):
                    values.append(elapsed_ms)
                else:
                    values.append(3600_000.0)
            except Exception:
                values.append(3600_000.0)

        if not values:
            return None

        avg = round(sum(values) / len(values), 3)
        if avg >= 3600_000.0:
            return None
        return avg

    def get_best_server(self):
        candidates = self.get_closest_servers(limit=25)

        best_server = None
        best_ping = None

        for server in candidates:
            latency = self._measure_server_latency(server, attempts=3)
            if latency is None:
                continue

            if best_ping is None or latency < best_ping:
                best_ping = latency
                best_server = dict(server)
                best_server["latency"] = latency

        if not best_server:
            raise SpeedtestLiteError("No server reachable")

        self.best = best_server
        self.ping = best_ping
        return best_server

    def _download_worker(
        self,
        base_url,
        timeout,
        stop_event,
        deadline,
        max_bytes,
        counters,
        lock,
    ):
        paths = [
            "random4000x4000.jpg",
            "random3500x3500.jpg",
            "random3000x3000.jpg",
            "random2500x2500.jpg",
            "random2000x2000.jpg",
            "random1500x1500.jpg",
            "random1000x1000.jpg",
            "random750x750.jpg",
            "random500x500.jpg",
        ]

        worker_session = requests.Session()
        worker_session.headers.update(self.session.headers)
        index = 0

        while not stop_event.is_set():
            if time.time() >= deadline:
                break

            with lock:
                if counters["bytes"] >= max_bytes:
                    break

            path = paths[index % len(paths)]
            index += 1

            stamp = int(time.time() * 1000)
            url = f"{base_url}/{path}?x={stamp}"

            try:
                with worker_session.get(url, stream=True, timeout=timeout) as response:
                    response.raise_for_status()

                    for chunk in response.iter_content(chunk_size=65536):
                        if stop_event.is_set() or time.time() >= deadline:
                            return

                        if not chunk:
                            continue

                        chunk_len = len(chunk)

                        with lock:
                            remaining = max_bytes - counters["bytes"]
                            if remaining <= 0:
                                return

                            if chunk_len > remaining:
                                counters["bytes"] += remaining
                                return

                            counters["bytes"] += chunk_len

            except Exception:
                continue

    def run_download_test(self, seconds=8, threads=4, max_megabytes=25):
        if not self.best:
            self.get_best_server()

        base_url = self.best["url"].rsplit("/", 1)[0]
        stop_event = threading.Event()
        deadline = time.time() + max(1, int(seconds))
        max_bytes = max(1, int(max_megabytes)) * 1024 * 1024

        counters = {"bytes": 0}
        lock = threading.Lock()
        workers = []

        start = time.perf_counter()

        for _ in range(max(1, int(threads))):
            thread = threading.Thread(
                target=self._download_worker,
                args=(
                    base_url,
                    self.timeout,
                    stop_event,
                    deadline,
                    max_bytes,
                    counters,
                    lock,
                ),
                daemon=True,
            )
            workers.append(thread)
            thread.start()

        for thread in workers:
            thread.join()

        elapsed = max(time.perf_counter() - start, 0.001)
        downloaded_bytes = counters["bytes"]
        mbps = (downloaded_bytes * 8) / elapsed / 1_000_000

        return {
            "server": self.best.get("name"),
            "sponsor": self.best.get("sponsor"),
            "server_id": self.best.get("id"),
            "ping": self.ping,
            "distance_km": round(self.best.get("d", 0), 2),
            "download_bytes": downloaded_bytes,
            "download_megabytes": round(downloaded_bytes / 1024 / 1024, 2),
            "elapsed_seconds": round(elapsed, 2),
            "download_mbps": round(mbps, 2),
        }

    def _upload_worker(
        self,
        base_url,
        timeout,
        stop_event,
        deadline,
        max_bytes,
        counters,
        lock,
    ):
        worker_session = requests.Session()
        worker_session.headers.update(self.session.headers)

        payload = b"0" * 65536
        multiplier = 16

        while not stop_event.is_set():
            if time.time() >= deadline:
                break

            with lock:
                remaining = max_bytes - counters["bytes"]
                if remaining <= 0:
                    break

            current_payload_size = min(len(payload) * multiplier, remaining)
            data = b"0" * current_payload_size

            stamp = int(time.time() * 1000)
            url = f"{base_url}/upload.php?x={stamp}"

            try:
                response = worker_session.post(
                    url,
                    data=data,
                    timeout=timeout,
                )

                if response.status_code != 200:
                    continue

                sent_bytes = len(data)

                with lock:
                    remaining = max_bytes - counters["bytes"]
                    if remaining <= 0:
                        return

                    if sent_bytes > remaining:
                        counters["bytes"] += remaining
                        return

                    counters["bytes"] += sent_bytes

            except Exception:
                continue

    def run_upload_test(self, seconds=8, threads=4, max_megabytes=10):
        if not self.best:
            self.get_best_server()

        base_url = self.best["url"].rsplit("/", 1)[0]
        stop_event = threading.Event()
        deadline = time.time() + max(1, int(seconds))
        max_bytes = max(1, int(max_megabytes)) * 1024 * 1024

        counters = {"bytes": 0}
        lock = threading.Lock()
        workers = []

        start = time.perf_counter()

        for _ in range(max(1, int(threads))):
            thread = threading.Thread(
                target=self._upload_worker,
                args=(
                    base_url,
                    self.timeout,
                    stop_event,
                    deadline,
                    max_bytes,
                    counters,
                    lock,
                ),
                daemon=True,
            )
            workers.append(thread)
            thread.start()

        for thread in workers:
            thread.join()

        elapsed = max(time.perf_counter() - start, 0.001)
        uploaded_bytes = counters["bytes"]
        mbps = (uploaded_bytes * 8) / elapsed / 1_000_000

        return {
            "server": self.best.get("name"),
            "sponsor": self.best.get("sponsor"),
            "server_id": self.best.get("id"),
            "ping": self.ping,
            "distance_km": round(self.best.get("d", 0), 2),
            "upload_bytes": uploaded_bytes,
            "upload_megabytes": round(uploaded_bytes / 1024 / 1024, 2),
            "elapsed_seconds": round(elapsed, 2),
            "upload_mbps": round(mbps, 2),
        }

    def run_trigger(self):
        self.get_config()
        self.get_servers()
        self.get_best_server()

        return {
            "isp": self.client.get("isp"),
            "ip": self.client.get("ip"),
            "server": self.best.get("name"),
            "sponsor": self.best.get("sponsor"),
            "ping": self.ping,
            "distance_km": round(self.best.get("d", 0), 2),
            "server_id": self.best.get("id"),
        }

    def run_trigger_and_speedtest(
        self,
        delay_before_test=2,
        no_download=False,
        no_upload=False,
        seconds=8,
        threads=4,
        download_max_megabytes=25,
        upload_max_megabytes=10,
    ):
        trigger_info = self.run_trigger()

        if delay_before_test > 0 and (not no_download or not no_upload):
            time.sleep(delay_before_test)

        result = {
            "trigger": trigger_info,
            "download": None,
            "upload": None,
        }

        if not no_download:
            result["download"] = self.run_download_test(
                seconds=seconds,
                threads=threads,
                max_megabytes=download_max_megabytes,
            )

        if not no_upload:
            result["upload"] = self.run_upload_test(
                seconds=seconds,
                threads=threads,
                max_megabytes=upload_max_megabytes,
            )

        return result


def main():
    parser = argparse.ArgumentParser(description="Speedtest Trigger Tool")

    parser.add_argument(
        "--no-download",
        action="store_true",
        help="Skip download test",
    )
    parser.add_argument(
        "--no-upload",
        action="store_true",
        help="Skip upload test",
    )
    parser.add_argument(
        "--seconds",
        type=int,
        default=8,
        help="Duration for download/upload test",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=4,
        help="Number of worker threads",
    )
    parser.add_argument(
        "--download-max-mb",
        type=int,
        default=25,
        help="Maximum MB for download test",
    )
    parser.add_argument(
        "--upload-max-mb",
        type=int,
        default=10,
        help="Maximum MB for upload test",
    )
    parser.add_argument(
        "--delay",
        type=int,
        default=2,
        help="Delay before starting tests after trigger",
    )

    args = parser.parse_args()

    st = SpeedtestLite(secure=True)

    try:
        result = st.run_trigger_and_speedtest(
            delay_before_test=args.delay,
            no_download=args.no_download,
            no_upload=args.no_upload,
            seconds=args.seconds,
            threads=args.threads,
            download_max_megabytes=args.download_max_mb,
            upload_max_megabytes=args.upload_max_mb,
        )
    except SpeedtestLiteError as exc:
        print(f"Error: {exc}")
        raise SystemExit(1)
    except requests.RequestException as exc:
        print(f"Network error: {exc}")
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("\nCancelled by user.")
        raise SystemExit(1)

    trigger = result["trigger"]

    print(f"ISP: {trigger['isp']} ({trigger['ip']})")
    print(
        f"Best Server: {trigger['sponsor']} - {trigger['server']} "
        f"[{trigger['distance_km']} km]"
    )
    print(f"Ping: {trigger['ping']} ms")

    if args.no_download and args.no_upload:
        print("Mode: CHECKER (no download/upload)")
        return

    if result["download"] is not None:
        download = result["download"]
        print(
            f"Download Test: {download['download_mbps']} Mbps "
            f"({download['download_megabytes']} MB in "
            f"{download['elapsed_seconds']}s)"
        )
    else:
        print("Download Test: SKIPPED (--no-download)")

    if result["upload"] is not None:
        upload = result["upload"]
        print(
            f"Upload Test: {upload['upload_mbps']} Mbps "
            f"({upload['upload_megabytes']} MB in "
            f"{upload['elapsed_seconds']}s)"
        )
    else:
        print("Upload Test: SKIPPED (--no-upload)")


if __name__ == "__main__":
    main()
