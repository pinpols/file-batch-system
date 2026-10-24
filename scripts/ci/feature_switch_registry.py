# 用途：提供功能开关注册表的加载与解析能力。
# 用法：由功能开关守卫导入 feature_switch_registry 模块并调用其公共函数。
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent.parent
REGISTRY_PATH = ROOT / "docs/runbook/feature-switch-registry.yml"


@dataclass(frozen=True)
class FeatureSwitch:
    key: str
    module: str
    env: tuple[str, ...]
    compose_required: bool
    helm_required: bool
    helm_env: tuple[str, ...]


def load_feature_switches(path: Path = REGISTRY_PATH) -> list[FeatureSwitch]:
    """读取 feature-switch-registry.yml 使用的受限 YAML 结构。
    平面注册表无需额外引入 PyYAML；解析器只支持当前文件需要的顶层 switches 列表、
    标量字段及列表字段，不充当通用 YAML 解析器。"""

    switches: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    current_list: str | None = None

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line.strip() or line.strip() == "switches:":
            continue

        stripped = line.strip()
        if stripped.startswith("- key:"):
            if current is not None:
                switches.append(current)
            current = {"key": _scalar(stripped.removeprefix("- key:"))}
            current_list = None
            continue

        if current is None:
            raise ValueError(f"unexpected registry line before first switch: {raw_line}")

        if stripped in {"env:", "helmEnv:"}:
            current_list = stripped[:-1]
            current[current_list] = []
            continue

        if stripped.startswith("- "):
            if current_list is None:
                raise ValueError(f"list item without active list: {raw_line}")
            current[current_list].append(_scalar(stripped.removeprefix("- ")))  # type: ignore[index]
            continue

        if ":" not in stripped:
            raise ValueError(f"unsupported registry line: {raw_line}")

        name, value = stripped.split(":", 1)
        current[name] = _scalar(value)
        current_list = None

    if current is not None:
        switches.append(current)

    return [_to_switch(item) for item in switches]


def compose_required_env_vars() -> set[str]:
    return {
        env
        for switch in load_feature_switches()
        if switch.compose_required
        for env in switch.env
    }


def helm_required_env_vars() -> set[str]:
    out: set[str] = set()
    for switch in load_feature_switches():
        if switch.helm_required:
            out.update(switch.helm_env or switch.env)
        elif switch.helm_env:
            out.update(switch.helm_env)
    return out


def _to_switch(item: dict[str, object]) -> FeatureSwitch:
    key = _required_str(item, "key")
    env = tuple(_required_list(item, "env"))
    helm_env = tuple(_optional_list(item, "helmEnv"))
    if not env:
        raise ValueError(f"{key}: env must not be empty")
    return FeatureSwitch(
        key=key,
        module=str(item.get("module", "")),
        env=env,
        compose_required=_bool(item.get("composeRequired", False)),
        helm_required=_bool(item.get("helmRequired", False)),
        helm_env=helm_env,
    )


def _required_str(item: dict[str, object], field: str) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"feature switch registry missing string field: {field}")
    return value


def _required_list(item: dict[str, object], field: str) -> list[str]:
    value = item.get(field)
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        raise ValueError(f"feature switch registry missing list field: {field}")
    return value


def _optional_list(item: dict[str, object], field: str) -> list[str]:
    value = item.get(field, [])
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        raise ValueError(f"feature switch registry field must be a list: {field}")
    return value


def _bool(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.lower()
        if lowered == "true":
            return True
        if lowered == "false":
            return False
    raise ValueError(f"expected boolean, got {value!r}")


def _scalar(value: str) -> object:
    stripped = value.strip().strip('"').strip("'")
    if stripped == "true":
        return True
    if stripped == "false":
        return False
    return stripped
