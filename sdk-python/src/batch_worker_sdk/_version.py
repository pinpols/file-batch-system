"""Version metadata for batch-worker-sdk.

Single source of truth for the package version. ``pyproject.toml`` reads
this value via ``[tool.hatch.version]`` so we never drift between the
package metadata and the runtime ``__version__``.
"""

from __future__ import annotations

__version__: str = "0.3.0a0"
