"""``python -m sample_tenant_worker`` 入口。"""

from __future__ import annotations

import asyncio

from sample_tenant_worker.main import main

if __name__ == "__main__":
    asyncio.run(main())
