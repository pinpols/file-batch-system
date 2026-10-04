variable "APP_IMAGE_TAG" {
  default = "local"
}

variable "BUILD_REVISION" {
  default = "unknown"
}

variable "OPS_TOOLBOX_IMAGE_TAG" {
  default = "local"
}

variable "OPS_TOOLBOX_PYTHON_VERSION" {
  default = "3.12"
}

variable "OPS_TOOLBOX_POSTGRES_CLIENT_MAJOR" {
  default = "17"
}

variable "KAFKA_IMAGE_TAG" {
  default = "4.1.2"
}

variable "MINIO_MC_IMAGE_REPOSITORY" {
  default = "bitnamilegacy/minio"
}

variable "MINIO_MC_IMAGE_TAG" {
  default = "2025.7.23-debian-12-r1"
}

// CI overlay 可向该抽象目标注入远程缓存，本地构建不依赖 GitHub Actions 环境。
target "_cache" {}

target "_app" {
  inherits   = ["_cache"]
  context    = "."
  dockerfile = "deploy/docker/Dockerfile.app"
  args = {
    BUILD_MODE     = "all"
    BUILD_REVISION = BUILD_REVISION
  }
}

target "console-api" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-console-api"
  }
  tags = ["batch-console-api:${APP_IMAGE_TAG}"]
}

target "trigger" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-trigger"
  }
  tags = ["batch-trigger:${APP_IMAGE_TAG}"]
}

target "orchestrator" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-orchestrator"
  }
  tags = ["batch-orchestrator:${APP_IMAGE_TAG}"]
}

target "worker-import" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-worker-import"
  }
  tags = ["batch-worker-import:${APP_IMAGE_TAG}"]
}

target "worker-export" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-worker-export"
  }
  tags = ["batch-worker-export:${APP_IMAGE_TAG}"]
}

target "worker-process" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-worker-process"
  }
  tags = ["batch-worker-process:${APP_IMAGE_TAG}"]
}

target "worker-dispatch" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-worker-dispatch"
  }
  tags = ["batch-worker-dispatch:${APP_IMAGE_TAG}"]
}

target "worker-atomic" {
  inherits = ["_app"]
  args = {
    MODULE = "batch-worker-atomic"
  }
  tags = ["batch-worker-atomic:${APP_IMAGE_TAG}"]
}

target "ops-toolbox" {
  context    = "."
  dockerfile = "deploy/docker/Dockerfile.ops-toolbox"
  args = {
    PYTHON_VERSION            = OPS_TOOLBOX_PYTHON_VERSION
    POSTGRES_CLIENT_MAJOR     = OPS_TOOLBOX_POSTGRES_CLIENT_MAJOR
    KAFKA_IMAGE_TAG           = KAFKA_IMAGE_TAG
    MINIO_MC_IMAGE_REPOSITORY = MINIO_MC_IMAGE_REPOSITORY
    MINIO_MC_IMAGE_TAG        = MINIO_MC_IMAGE_TAG
  }
  tags = ["batch-ops-toolbox:${OPS_TOOLBOX_IMAGE_TAG}"]
}

group "default" {
  targets = [
    "console-api",
    "trigger",
    "orchestrator",
    "worker-import",
    "worker-export",
    "worker-process",
    "worker-dispatch",
    "worker-atomic",
  ]
}

group "ci" {
  targets = [
    "console-api",
    "trigger",
    "orchestrator",
    "worker-import",
    "worker-export",
    "worker-process",
    "worker-dispatch",
    "worker-atomic",
    "ops-toolbox",
  ]
}

group "tools" {
  targets = [
    "ops-toolbox",
  ]
}
