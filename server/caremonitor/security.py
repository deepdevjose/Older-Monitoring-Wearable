"""Password, token and authenticated at-rest encryption primitives."""
import base64
import hashlib
import hmac
import json
import os
import secrets
from argon2 import PasswordHasher, Type
from argon2.exceptions import VerificationError, InvalidHashError
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSWORDS = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2, type=Type.ID)
DUMMY_PASSWORD_HASH = PASSWORDS.hash(secrets.token_urlsafe(32))


def hash_password(password: str) -> str:
    return PASSWORDS.hash(password)


def check_password(hashed: str, password: str) -> bool:
    try:
        return PASSWORDS.verify(hashed, password)
    except (VerificationError, InvalidHashError):
        return False


def new_token() -> str:
    return secrets.token_urlsafe(48)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class Vault:
    def __init__(self, key: bytes):
        self._key = key
        self._aes = AESGCM(key)

    def lookup(self, scope: str, value: str) -> str:
        return hmac.new(self._key, (scope + ":" + value).encode(), hashlib.sha256).hexdigest()

    def seal(self, value, aad: str) -> str:
        plain = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        nonce = os.urandom(12)
        return base64.urlsafe_b64encode(nonce + self._aes.encrypt(nonce, plain, aad.encode())).decode()

    def open(self, value: str, aad: str):
        data = base64.urlsafe_b64decode(value.encode())
        return json.loads(self._aes.decrypt(data[:12], data[12:], aad.encode()))
