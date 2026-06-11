# Phase 5 final-gate tooling: mint RS256 JWTs + a static JWKS for the
# substrate.auth.mode=oidc live audit on the Docker-less dev box.
# Uses only `cryptography` (already installed). Nothing here touches any
# network or cloud — the JWKS is served by a local python http.server.
import base64
import json
import sys
import time
import uuid
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def int_to_b64url(value: int) -> str:
    length = (value.bit_length() + 7) // 8
    return b64url(value.to_bytes(length, "big"))


def main(out_dir: str) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub = key.public_key().public_numbers()
    kid = "phase5-gate-key"

    jwks = {
        "keys": [
            {
                "kty": "RSA",
                "use": "sig",
                "alg": "RS256",
                "kid": kid,
                "n": int_to_b64url(pub.n),
                "e": int_to_b64url(pub.e),
            }
        ]
    }
    with open(f"{out_dir}/jwks.json", "w") as f:
        json.dump(jwks, f)

    now = int(time.time())

    def mint(name: str, claims: dict) -> None:
        header = {"alg": "RS256", "typ": "JWT", "kid": kid}
        payload = {"iat": now, "exp": now + 3600, **claims}
        signing_input = (
            b64url(json.dumps(header).encode())
            + "."
            + b64url(json.dumps(payload).encode())
        )
        sig = key.sign(
            signing_input.encode("ascii"), padding.PKCS1v15(), hashes.SHA256()
        )
        token = signing_input + "." + b64url(sig)
        with open(f"{out_dir}/{name}.jwt", "w") as f:
            f.write(token)
        print(f"minted {name}: sub={claims['sub']}")

    admin_sub = str(uuid.uuid4())
    operator_sub = str(uuid.uuid4())

    mint(
        "admin",
        {
            "sub": admin_sub,
            "name": "Phase5 Gate Admin",
            "preferred_username": "gate-admin",
            "realm_access": {"roles": ["substrate-admin"]},
        },
    )
    mint(
        "operator",
        {
            "sub": operator_sub,
            "name": "Phase5 Gate Operator",
            "preferred_username": "gate-operator",
            "realm_access": {"roles": ["substrate-operator"]},
        },
    )
    with open(f"{out_dir}/subs.json", "w") as f:
        json.dump({"admin": admin_sub, "operator": operator_sub}, f)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
