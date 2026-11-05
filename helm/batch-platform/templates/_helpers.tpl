{{/*
Expand the name of the chart.
*/}}
{{- define "batch-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "batch-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart label.
*/}}
{{- define "batch-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "batch-platform.labels" -}}
helm.sh/chart: {{ include "batch-platform.chart" . }}
{{ include "batch-platform.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels (requires .component to be set in calling context).
*/}}
{{- define "batch-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "batch-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- if .component }}
app.kubernetes.io/component: {{ .component }}
{{- end }}
{{- end }}

{{/*
Image reference helper.  Usage: include "batch-platform.image" (dict "root" . "name" "batch-console-api")
*/}}
{{/*
  Digest 优先级最高；无 digest 时 values.image.tag（显式覆盖）> Chart.AppVersion（默认）
  Chart.AppVersion 必须与 pom.xml <revision> 保持一致（两处同步改）。
*/}}
{{- define "batch-platform.image" -}}
{{- $references := .root.Values.image.references | default dict -}}
{{- $reference := index $references .name | default "" -}}
{{- $reg := .root.Values.image.registry -}}
{{- $tag := default .root.Chart.AppVersion .root.Values.image.tag -}}
{{- $digest := default "" .root.Values.image.digest -}}
{{- if $reference -}}
{{- if not (regexMatch "^([a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]+)?/)?[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}$" $reference) -}}
{{- fail (printf "image.references.%s must be an immutable sha256 image reference" .name) -}}
{{- end -}}
{{- $reference -}}
{{- else if $digest -}}
{{- if $reg -}}
{{- printf "%s/%s@%s" $reg .name $digest -}}
{{- else -}}
{{- printf "%s@%s" .name $digest -}}
{{- end -}}
{{- else if $reg -}}
{{- printf "%s/%s:%s" $reg .name $tag -}}
{{- else -}}
{{- printf "%s:%s" .name $tag -}}
{{- end -}}
{{- end }}

{{/*
imagePullSecrets block.
*/}}
{{- define "batch-platform.imagePullSecrets" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Name of the shared ConfigMap.
*/}}
{{- define "batch-platform.configmapName" -}}
{{ include "batch-platform.fullname" . }}-config
{{- end }}

{{/*
Name of the shared Secret.
*/}}
{{- define "batch-platform.secretName" -}}
{{- if .Values.security.existingSecretName -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$" .Values.security.existingSecretName) -}}
{{- fail "security.existingSecretName must be a valid Kubernetes Secret name" -}}
{{- end -}}
{{ .Values.security.existingSecretName }}
{{- else -}}
{{ include "batch-platform.fullname" . }}-secret
{{- end -}}
{{- end }}

{{/*
Common envFrom block (ConfigMap + Secret).
*/}}
{{- define "batch-platform.envFrom" -}}
envFrom:
  - configMapRef:
      name: {{ include "batch-platform.configmapName" . }}
  - secretRef:
      name: {{ include "batch-platform.secretName" . }}
{{- end }}

{{/*
Worker 平台库独立凭据（ARCH-20261103-01）：设置 platformDb.credentialsSecretName 时，以显式 env 覆盖共享
Secret 中的平台库账号，配合 scripts/db/platform-worker-roles.sql 授予的最小权限角色使用。
该 Secret 需包含 BATCH_PLATFORM_DB_USERNAME 与 BATCH_PLATFORM_DB_PASSWORD；未设置时不渲染，沿用共享账号。
*/}}
{{- define "batch-platform.workerPlatformDbCredentialsEnv" -}}
{{- $secretName := (.platformDb | default dict).credentialsSecretName -}}
{{- if $secretName }}
- name: BATCH_PLATFORM_DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: BATCH_PLATFORM_DB_USERNAME
- name: BATCH_PLATFORM_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: BATCH_PLATFORM_DB_PASSWORD
{{- end }}
{{- end }}

{{/*
控制面平台库独立凭据（ARCH-20261103-01）：设置 <component>.platformDb.credentialsSecretName 时，运行时账号改用
该 Secret 中 scripts/db/platform-control-plane-roles.sql 授权的最小权限角色。
- 参数：dict "root" 根上下文 "svc" 组件 values "migrates" 是否执行 Flyway "console" 是否为 Console
- migrates：Flyway 仍以共享 ConfigMap/Secret 中的 owner 账号迁移（BATCH_PLATFORM_FLYWAY_USERNAME/PASSWORD）
- console：Console 主库与只读副本账号（BATCH_CONSOLE_PRIMARY_USER 与 BATCH_CONSOLE_REPLICA_USER 及其密码）一并改用该 Secret
未设置时不渲染，沿用共享账号。
*/}}
{{- define "batch-platform.controlPlanePlatformDbCredentialsEnv" -}}
{{- $secretName := (.svc.platformDb | default dict).credentialsSecretName -}}
{{- if $secretName }}
{{- include "batch-platform.workerPlatformDbCredentialsEnv" .svc }}
{{- if .console }}
{{- range $target := list "PRIMARY" "REPLICA" }}
- name: BATCH_CONSOLE_{{ $target }}_USER
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: BATCH_PLATFORM_DB_USERNAME
- name: BATCH_CONSOLE_{{ $target }}_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: BATCH_PLATFORM_DB_PASSWORD
{{- end }}
{{- end }}
{{- if .migrates }}
- name: BATCH_PLATFORM_FLYWAY_USERNAME
  valueFrom:
    configMapKeyRef:
      name: {{ include "batch-platform.configmapName" .root }}
      key: BATCH_PLATFORM_DB_USERNAME
- name: BATCH_PLATFORM_FLYWAY_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "batch-platform.secretName" .root }}
      key: BATCH_PLATFORM_DB_PASSWORD
{{- end }}
{{- end }}
{{- end }}

{{/*
启动期配置摘要。ConfigMap 或 Secret 的渲染结果变化时，Pod template hash 随之变化，
Deployment/StatefulSet 因此自动执行滚动更新。摘要不暴露 Secret 明文。
*/}}
{{- define "batch-platform.configChecksumAnnotations" -}}
checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
checksum/secret: {{ include (print $.Template.BasePath "/secret.yaml") . | sha256sum }}
{{- if .Values.security.existingSecretRevision }}
checksum/external-secret: {{ .Values.security.existingSecretRevision | sha256sum }}
{{- end }}
{{- end }}

{{/*
Unique worker runtime identity. The stable application workerCode remains the Kafka routing pool;
the Pod UID only distinguishes replicas in worker_registry so capacity is not last-heartbeat-wins.
*/}}
{{- define "batch-platform.workerIdentityEnv" -}}
- name: BATCH_WORKER_IDENTITY_INSTANCE_ID
  valueFrom:
    fieldRef:
      fieldPath: metadata.uid
{{- end }}

{{/*
Graceful shutdown block. Renders pod-level terminationGracePeriodSeconds (siblings
to containers/volumes) AND container-level lifecycle.preStop sleep separately.

Usage:
  spec:
    {{- include "batch-platform.gracefulShutdownPod" $svc.gracefulShutdown | nindent 6 }}
    containers:
      - name: foo
        ...
        {{- include "batch-platform.gracefulShutdownLifecycle" $svc.gracefulShutdown | nindent 10 }}

Defaults: terminationGracePeriodSeconds=90, preStop sleep=15.
- 90s 让 ShedLock 释放 + Spring graceful shutdown drain in-flight requests
- preStop sleep 15s 让 k8s Service endpoints 收敛（避免 SLB 仍把流量打到正在退出的 pod）

可在 values.yaml 每个 component 覆盖：
  orchestrator:
    gracefulShutdown:
      terminationGracePeriodSeconds: 120  # ShedLock + 长 archive 任务
      preStopSleepSeconds: 20
*/}}
{{- define "batch-platform.gracefulShutdownPod" -}}
terminationGracePeriodSeconds: {{ default 90 .terminationGracePeriodSeconds }}
{{- end }}

{{- define "batch-platform.gracefulShutdownLifecycle" -}}
{{- $sleep := default 15 .preStopSleepSeconds -}}
{{- if gt (int $sleep) 0 }}
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep {{ $sleep }}"]
{{- end }}
{{- end }}

{{/*
Standard pod scheduling fields.
*/}}
{{- define "batch-platform.podSecurityDefaults" -}}
automountServiceAccountToken: false
securityContext:
  {{- toYaml .Values.podSecurityContext | nindent 2 }}
{{- end }}

{{/*
Standard pod scheduling fields.
*/}}
{{- define "batch-platform.scheduling" -}}
{{- with .Values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
JVM 诊断目录。所有应用的 JAVA_OPTS 都会把 GC/JFR/heap dump 写入
/var/log/app，因此该卷不能只在个别 workload 上挂载。sizeLimit 防止诊断
文件挤爆节点磁盘。
*/}}
{{- define "batch-platform.diagnosticsVolumeMount" -}}
- name: diagnostics
  mountPath: /var/log/app
{{- end }}

{{- define "batch-platform.diagnosticsVolume" -}}
- name: diagnostics
  emptyDir:
    sizeLimit: {{ .Values.diagnostics.sizeLimit | quote }}
{{- end }}
