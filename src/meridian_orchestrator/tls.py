"""Self-signed TLS cert provisioning for the local MCP endpoint.

Claude Desktop's MCP connector UI rejects ``http://`` URLs, so the daemon
serves Streamable-HTTP over TLS. The cert is self-signed, generated on first
run, persisted under ``<data_dir>/certs/``, and regenerated only when missing
or expired. Phase 2 will replace this with proper renewal automation; the
deferred-work entry lives in ``BACKLOG.md``.

Cert subject: CN=meridian-orchestrator-local. SANs: localhost, 127.0.0.1, ::1
(without these, browsers and Claude Desktop reject the cert when connecting
to a hostname/IP that isn't the CN). RSA-2048, SHA-256 signature, 365-day
validity.
"""

from __future__ import annotations

import getpass
import ipaddress
import logging
import subprocess
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

logger = logging.getLogger(__name__)

CERT_FILENAME = "server.crt"
KEY_FILENAME = "server.key"
COMMON_NAME = "meridian-orchestrator-local"
KEY_SIZE = 2048
VALIDITY_DAYS = 365


def ensure_cert(certs_dir: Path) -> tuple[Path, Path, str]:
    """Return paths to a valid cert and key, generating them if needed.

    Returns:
        ``(cert_path, key_path, sha256_fingerprint)``. The fingerprint is the
        cert's SHA-256 hex digest formatted with colons (``AB:CD:...``) — log
        it on startup so the operator can compare it against what Claude
        Desktop trusted.
    """
    cert_path = certs_dir / CERT_FILENAME
    key_path = certs_dir / KEY_FILENAME

    if _cert_is_valid(cert_path):
        return cert_path, key_path, _fingerprint(cert_path)

    certs_dir.mkdir(parents=True, exist_ok=True)
    _generate_self_signed(cert_path, key_path)
    _restrict_owner_only(key_path)
    _restrict_owner_only(cert_path)
    return cert_path, key_path, _fingerprint(cert_path)


def _cert_is_valid(cert_path: Path) -> bool:
    if not cert_path.exists():
        return False
    try:
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    except ValueError:
        return False
    return cert.not_valid_after_utc > datetime.now(tz=UTC)


def _fingerprint(cert_path: Path) -> str:
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    digest = cert.fingerprint(hashes.SHA256()).hex().upper()
    return ":".join(digest[i : i + 2] for i in range(0, len(digest), 2))


def _generate_self_signed(cert_path: Path, key_path: Path) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=KEY_SIZE)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, COMMON_NAME)])
    now = datetime.now(tz=UTC)
    san = x509.SubjectAlternativeName(
        [
            x509.DNSName("localhost"),
            x509.DNSName(COMMON_NAME),
            x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
            x509.IPAddress(ipaddress.IPv6Address("::1")),
        ]
    )
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=VALIDITY_DAYS))
        .add_extension(san, critical=False)
        .add_extension(
            x509.BasicConstraints(ca=False, path_length=None), critical=True
        )
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                key_encipherment=True,
                content_commitment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )


def _restrict_owner_only(path: Path) -> None:
    """Restrict a file so only the current user can read or write it.

    POSIX: chmod 0600. Windows: ``icacls`` removes inheritance and grants
    Full Control to the current user only. icacls failures are logged but
    not fatal — the cert/key are usable; the tightening is defense-in-depth.
    """
    if sys.platform == "win32":
        username = getpass.getuser()
        try:
            subprocess.run(
                ["icacls", str(path), "/inheritance:r"],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["icacls", str(path), "/grant:r", f"{username}:F"],
                check=True,
                capture_output=True,
                text=True,
            )
        except (subprocess.CalledProcessError, FileNotFoundError) as exc:
            logger.warning(
                "tls.acl_restrict_failed path=%s error=%s",
                path,
                getattr(exc, "stderr", str(exc)),
            )
    else:
        path.chmod(0o600)
