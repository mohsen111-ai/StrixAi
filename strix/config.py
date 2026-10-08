"""Config loading: providers, role -> model fallback chains, settings."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml

DEFAULT_PATH = Path(__file__).with_name("default.yaml")
ROLES = ("planner", "coder", "fast", "longctx")


@dataclass
class Provider:
    name: str
    base_url: str
    api_key_env: str | None = None
    headers: dict = field(default_factory=dict)

    @property
    def api_key(self) -> str | None:
        return os.environ.get(self.api_key_env) if self.api_key_env else None


@dataclass(frozen=True)
class ModelRef:
    provider: str
    model: str

    def __str__(self) -> str:
        return f"{self.provider}:{self.model}"

    @classmethod
    def parse(cls, spec: str) -> "ModelRef":
        if ":" not in spec:
            raise ValueError(f"model spec '{spec}' must look like 'provider:model-id'")
        provider, model = spec.split(":", 1)
        return cls(provider.strip(), model.strip())


@dataclass
class Settings:
    mode: str = "auto-edit"
    planning: bool = True
    max_steps: int = 40
    context_tokens: int = 100_000
    temperature: float = 0.2
    bad_calls_before_switch: int = 3


@dataclass
class Config:
    providers: dict[str, Provider]
    roles: dict[str, list[ModelRef]]
    settings: Settings
    source: str = "default"

    def chain(self, role: str) -> list[ModelRef]:
        chain = self.roles.get(role)
        if chain:
            return chain
        # fall back to the coder chain so a minimal config still works
        return self.roles.get("coder", [])

    def provider_for(self, ref: ModelRef) -> Provider:
        try:
            return self.providers[ref.provider]
        except KeyError:
            raise ValueError(f"model {ref} uses unknown provider '{ref.provider}'") from None

    def missing_keys(self) -> list[str]:
        used = {r.provider for chain in self.roles.values() for r in chain}
        out = []
        for name in sorted(used):
            p = self.providers.get(name)
            if p and p.api_key_env and not p.api_key:
                out.append(p.api_key_env)
        return out


def _search_paths(explicit: str | None) -> list[Path]:
    if explicit:
        return [Path(explicit)]
    return [Path.cwd() / "strix.yaml", Path.home() / ".config" / "strix" / "strix.yaml"]


def parse_config(data: dict, source: str = "inline") -> Config:
    providers = {}
    for name, p in (data.get("providers") or {}).items():
        providers[name] = Provider(name, p["base_url"], p.get("api_key_env"), p.get("headers") or {})
    roles = {r: [ModelRef.parse(s) for s in specs or []] for r, specs in (data.get("roles") or {}).items()}
    s = data.get("settings") or {}
    known = {k: v for k, v in s.items() if k in Settings.__dataclass_fields__}
    cfg = Config(providers, roles, Settings(**known), source)
    if not cfg.chain("coder"):
        raise ValueError("config needs at least a 'coder' role with one model")
    return cfg


def load_config(path: str | None = None) -> Config:
    for p in _search_paths(path):
        if p.is_file():
            return parse_config(yaml.safe_load(p.read_text()) or {}, str(p))
    if path:
        raise FileNotFoundError(path)
    return parse_config(yaml.safe_load(DEFAULT_PATH.read_text()), "built-in default")


def load_dotenv(path: Path | None = None) -> None:
    """Tiny .env reader so users can keep OPENROUTER_API_KEY next to the project."""
    p = path or Path.cwd() / ".env"
    if not p.is_file():
        return
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip().removeprefix("export "), v.strip().strip("'\""))
