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
  Tag 优先级：values.image.tag（显式覆盖）> Chart.AppVersion（默认）
  Chart.AppVersion 必须与 pom.xml <revision> 保持一致（两处同步改）。
*/}}
{{- define "batch-platform.image" -}}
{{- $reg := .root.Values.image.registry -}}
{{- $tag := default .root.Chart.AppVersion .root.Values.image.tag -}}
{{- if $reg -}}
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
{{ include "batch-platform.fullname" . }}-secret
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
