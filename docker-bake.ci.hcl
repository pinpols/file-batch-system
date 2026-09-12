// GitHub Actions BuildKit 远程缓存。scope 按 Java 镜像组共享，保留所有中间层以复用 Maven 依赖和 builder。
target "_cache" {
  cache-from = ["type=gha,scope=batch-java-images"]
  cache-to   = ["type=gha,scope=batch-java-images,mode=max"]
}
