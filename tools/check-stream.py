"""Verify real multipart JPEGs through adb forward tcp:18080 tcp:8080."""
import hashlib
import http.client
import json
import struct
import time

def dimensions(data):
    pos = 2
    while pos < len(data):
        if data[pos] != 255:
            raise ValueError("Invalid JPEG marker")
        while data[pos] == 255:
            pos += 1
        marker = data[pos]
        pos += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        length = struct.unpack_from(">H", data, pos)[0]
        if marker in (0xC0, 0xC1, 0xC2):
            height, width = struct.unpack_from(">HH", data, pos + 3)
            return width, height
        pos += length
    raise ValueError("No JPEG dimensions")

connection = http.client.HTTPConnection("127.0.0.1", 18080, timeout=25)
connection.request("GET", "/mjpeg")
response = connection.getresponse()
assert response.status == 200, response.status
assert "multipart/x-mixed-replace" in response.getheader("Content-Type", "")
times, sizes, hashes, sequences = [], [], [], []
for _ in range(8):
    line = response.readline()
    while line == b"\r\n":
        line = response.readline()
    assert line.startswith(b"--heycyan-frame"), line
    headers = {}
    while True:
        line = response.readline()
        if line == b"\r\n":
            break
        if not line:
            raise EOFError("Stream stopped before eight fresh frames")
        key, value = line.decode("ascii").split(":", 1)
        headers[key.lower()] = value.strip()
    size = int(headers["content-length"])
    assert 0 < size < 1_000_000, size
    data = response.read(size)
    assert len(data) == size and data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9")
    assert dimensions(data) == (1600, 1200), dimensions(data)
    times.append(time.monotonic())
    sizes.append(size)
    hashes.append(hashlib.sha256(data).hexdigest())
    sequences.append(int(headers["x-frame-sequence"]))
assert all(b > a for a, b in zip(sequences, sequences[1:])), sequences
assert len(set(hashes)) > 1, "Frames did not change"
print(json.dumps({"frames": len(sizes), "dimensions": [1600, 1200], "bytes_min": min(sizes), "bytes_max": max(sizes), "distinct_frames": len(set(hashes)), "average_interval_seconds": round((times[-1]-times[0])/(len(times)-1), 3), "sequences": sequences}))
connection.close()
