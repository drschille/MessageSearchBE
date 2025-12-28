#!/usr/bin/env python3
import argparse
import base64
import hashlib
import hmac
import json
import os
import time
import uuid


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def parse_roles(value: str) -> list[str]:
    roles = [item.strip() for item in value.split(",")]
    return [role for role in roles if role]


def main() -> None:
    parser = argparse.ArgumentParser(description="Issue a local HS256 JWT for MessageSearchBE.")
    parser.add_argument("--issuer", default=os.getenv("JWT_ISSUER", "app"))
    parser.add_argument("--audience", default=os.getenv("JWT_AUDIENCE", "app-clients"))
    parser.add_argument("--secret", default=os.getenv("JWT_SECRET", "dev-secret"))
    parser.add_argument("--sub", default=os.getenv("JWT_SUB"))
    parser.add_argument("--roles", default=os.getenv("JWT_ROLES", "editor"))
    parser.add_argument("--ttl-seconds", type=int, default=3600)
    args = parser.parse_args()

    subject = args.sub or str(uuid.uuid4())
    roles = parse_roles(args.roles)
    if not roles:
        raise SystemExit("roles must include at least one role")

    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    payload = {
        "iss": args.issuer,
        "aud": args.audience,
        "sub": subject,
        "roles": roles,
        "iat": now,
        "exp": now + args.ttl_seconds,
    }

    segments = [
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
        b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
    ]
    signing_input = ".".join(segments).encode("ascii")
    signature = hmac.new(args.secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    token = ".".join(segments + [b64url(signature)])
    print(token)


if __name__ == "__main__":
    main()
