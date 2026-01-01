#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib import error as urlerror
from urllib import request as urlrequest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Batch import documents into MessageSearch.")
    parser.add_argument("--base-url", required=True, help="Base URL for the API, e.g. http://localhost:8080")
    parser.add_argument("--token", required=True, help="JWT bearer token")
    parser.add_argument("--input", required=True, help="Directory of .txt/.md files or a JSON file")
    parser.add_argument("--language-code", default="en-US", help="Language code for documents")
    parser.add_argument("--batch-size", type=int, default=50, help="Documents per batch request")
    parser.add_argument("--retries", type=int, default=3, help="Retries for transient errors")
    parser.add_argument("--format", choices=["dir", "json"], help="Force input format")
    parser.add_argument(
        "--split",
        default="blankline",
        help="Paragraph split rule: blankline or delimiter:<TEXT> (example: delimiter:---)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print payload counts without sending")
    return parser.parse_args()


def split_paragraphs(text: str, mode: str) -> list[str]:
    if mode == "blankline":
        parts = [part.strip() for part in re.split(r"\n\s*\n+", text) if part.strip()]
        return parts
    if mode.startswith("delimiter:"):
        delimiter = mode[len("delimiter:") :]
        if not delimiter:
            raise ValueError("delimiter must not be empty")
        parts = [part.strip() for part in text.split(delimiter) if part.strip()]
        return parts
    raise ValueError(f"unsupported split rule: {mode}")
    return parts


def build_doc_from_text(path: Path, language_code: str, split_mode: str) -> dict:
    content = path.read_text(encoding="utf-8")
    paragraphs = split_paragraphs(content, split_mode)
    if not paragraphs:
        raise ValueError(f"empty document: {path}")
    return {
        "title": path.stem,
        "languageCode": language_code,
        "paragraphs": [
            {"position": idx, "body": body, "languageCode": language_code}
            for idx, body in enumerate(paragraphs)
        ],
        "publish": True,
    }


def load_documents_from_dir(path: Path, language_code: str, split_mode: str) -> list[dict]:
    if not path.is_dir():
        raise ValueError(f"input directory not found: {path}")
    docs = []
    for entry in sorted(path.iterdir()):
        if entry.is_file() and entry.suffix.lower() in {".txt", ".md"}:
            docs.append(build_doc_from_text(entry, language_code, split_mode))
    if not docs:
        raise ValueError(f"no .txt or .md files found in {path}")
    return docs


def load_documents_from_json(path: Path) -> list[dict]:
    if not path.is_file():
        raise ValueError(f"input file not found: {path}")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        return raw
    if isinstance(raw, dict) and isinstance(raw.get("documents"), list):
        return raw["documents"]
    raise ValueError("expected a JSON array of documents or an object with a documents array")


def chunked(items: list[dict], size: int) -> list[list[dict]]:
    if size <= 0:
        raise ValueError("batch-size must be positive")
    return [items[i : i + size] for i in range(0, len(items), size)]


def post_json(url: str, token: str, payload: dict, retries: int) -> dict:
    body = json.dumps(payload).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    for attempt in range(retries + 1):
        req = urlrequest.Request(url, data=body, headers=headers, method="POST")
        try:
            with urlrequest.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urlerror.HTTPError as exc:
            retryable = exc.code in {429, 500, 502, 503, 504}
            if retryable and attempt < retries:
                time.sleep(0.5 * (2**attempt))
                continue
            details = exc.read().decode("utf-8")
            raise RuntimeError(f"HTTP {exc.code}: {details}") from exc
        except urlerror.URLError as exc:
            if attempt < retries:
                time.sleep(0.5 * (2**attempt))
                continue
            raise RuntimeError(f"request failed: {exc}") from exc
    raise RuntimeError("request failed after retries")


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    fmt = args.format
    if fmt is None:
        fmt = "dir" if input_path.is_dir() else "json"
    if fmt == "dir":
        documents = load_documents_from_dir(input_path, args.language_code, args.split)
    else:
        documents = load_documents_from_json(input_path)
    if args.dry_run:
        print(f"loaded {len(documents)} documents")
        return 0
    batches = chunked(documents, args.batch_size)
    url = args.base_url.rstrip("/") + "/v1/documents:batch"
    total_created = 0
    total_failed = 0
    for idx, batch in enumerate(batches, start=1):
        payload = {"documents": batch}
        result = post_json(url, args.token, payload, args.retries)
        created = int(result.get("created", 0))
        failed = int(result.get("failed", 0))
        total_created += created
        total_failed += failed
        print(f"batch {idx}/{len(batches)}: created={created} failed={failed}")
        if failed:
            for item in result.get("results", []):
                if item.get("error"):
                    print(f"  index={item.get('index')}: {item.get('error')}")
    print(f"done: created={total_created} failed={total_failed}")
    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
