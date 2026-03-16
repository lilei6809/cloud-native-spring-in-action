helm install eg oci://docker.io/envoyproxy/gateway-helm --version v1.7.1 -n envoy-gateway-system --create-namespace

安装命令：
# 先安装 cert-manager（OTel Operator 依赖）
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml

# 等 cert-manager ready 后安装 OTel Operator
kubectl apply -f https://github.com/open-telemetry/opentelemetry-operator/releases/latest/download/opentelemetry-operator.yaml
