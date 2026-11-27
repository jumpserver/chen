"""Run against a disposable Chen standalone server configured with catalog_*.sql."""
import argparse
import json
import time
import uuid
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("selector")
    parser.add_argument("--url", default="http://127.0.0.1:8083")
    args = parser.parse_args()
    base = args.url.rstrip("/") + "/chen/api/v1/"
    token = ""

    def api(path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json", "token": token}
        with urlopen(Request(base + path, data=data, headers=headers), timeout=10) as response:
            return json.load(response)

    session = api("sessions", {"selector": args.selector, "request_id": uuid.uuid4().hex})
    token = session["token"]
    sid = "sessions/" + session["session"]["id"]
    names = []
    query = {"limit": 1}
    while True:
        page = api(sid + "/catalog/databases?" + urlencode(query))
        names.extend(item["name"] for item in page["items"])
        if not page["has_more"]:
            break
        assert len(names) < 100, "cursor traversal did not terminate"
        query["cursor"] = page["next_cursor"]
    assert "chen_qa" in names and "chen_hidden" not in names and sorted(set(names)) == names
    objects = api(sid + "/catalog/objects?database=chen_qa")
    assert [item["name"] for item in objects["items"]] == ["Alpha", "v_report"]
    columns = api(sid + "/catalog/columns?database=chen_qa&table=Alpha")
    assert [item["name"] for item in columns["items"]] == ["id", "label"]

    console = api(sid + "/consoles", {"database": "chen_qa"})
    execution = api("consoles/" + console["id"] + "/executions",
                    {"sql": "SELECT 1", "request_id": uuid.uuid4().hex})
    for _ in range(100):
        result = api("executions/" + execution["id"])
        if result["status"] in ("succeeded", "failed", "canceled"):
            break
        time.sleep(0.1)
    assert result["status"] == "succeeded", result["status"]
    rows = api("results/" + result["result_ids"][0] + "/rows")["rows"]
    assert len(rows) == 1 and str(rows[0][0]) == "1"
    print(args.selector, "passed catalog pagination, object visibility, columns and editor SELECT 1")


if __name__ == "__main__":
    main()
