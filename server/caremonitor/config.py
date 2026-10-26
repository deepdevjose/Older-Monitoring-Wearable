"""Configuration. Importing this module never creates runtime files."""
from dataclasses import dataclass, field
from pathlib import Path
import os


@dataclass
class Settings:
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("CAREMONITOR_HOME", ".local/runtime")).expanduser())
    session_hours: int = 24
    max_body_bytes: int = 65536
    scheduler_interval_seconds: float = 15.0
    enforce_https: bool = True  # HTTP is available only through an explicit test/development override.
    expose_docs: bool = False

    def __post_init__(self):
        self.data_dir = Path(self.data_dir).expanduser().resolve()
        if self.session_hours < 1 or self.session_hours > 24:
            raise ValueError("Sessions must expire within 24 hours")

    @property
    def key_path(self) -> Path:
        return self.data_dir / "encryption.key"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "caremonitor.sqlite3"

    def initialize_key(self) -> Path:
        """Explicit one-time bootstrap; never overwrite an existing encryption key."""
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.data_dir.chmod(0o700)
        try:
            fd = os.open(self.key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            self.load_key()
            return self.key_path
        with os.fdopen(fd, "wb") as stream:
            stream.write(os.urandom(32))
            stream.flush()
            os.fsync(stream.fileno())
        return self.key_path

    def load_key(self) -> bytes:
        if not self.key_path.exists():
            raise RuntimeError("Encryption key missing. Run caremonitor init before starting the server.")
        if self.key_path.is_symlink():
            raise RuntimeError("Encryption key must be a regular private file")
        if self.key_path.stat().st_mode & 0o077:
            raise RuntimeError("Encryption key must have mode 0600")
        key = self.key_path.read_bytes()
        if len(key) != 32:
            raise RuntimeError("Encryption key must contain exactly 32 random bytes")
        return key
