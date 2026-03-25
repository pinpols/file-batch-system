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
{{- define "batch-platform.image" -}}
{{- $reg := .root.Values.image.registry }}
{{- $tag := .root.Values.image.tag }}
{{- if $reg }}
{{- printf "%s/%s:%s" $reg .name $tag }}
{{- else }}
{{- printf "%s:%s" .name $tag }}
{{- end }}
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
