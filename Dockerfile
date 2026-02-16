# ==============================================================================
# コンテナベースのLambda用イメージ
# ============================================================================== 
ARG TARGET_ARCH=arm64
ARG DD_EXTENSION_TAG=latest

# === Builder stage: GradleでJAR + 依存をビルド ===
FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY . .
# Lambda用の成果物を作成（jar + 依存コピー）
RUN gradle clean jar copyRuntimeLibs --no-daemon

# === Datadog Extension stage ===
FROM public.ecr.aws/datadog/lambda-extension:${DD_EXTENSION_TAG} AS dd-ext

# === Runner stage (Lambda Java 21) ===
FROM public.ecr.aws/lambda/java:21.2025.05.04.06-${TARGET_ARCH} AS runner
COPY --from=dd-ext /opt/. /opt/
COPY --from=builder /workspace/build/libs/*.jar /var/task/lib/
COPY --from=builder /workspace/build/lib/*.jar /var/task/lib/

ARG CLI_ARCH=aarch64
RUN dnf update -y && \
    dnf install -y \
      findutils \
      git \
      tar \
      unzip \
      wget && \
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${CLI_ARCH}.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install && \
    rm -rf awscliv2.zip aws && \
    curl -fsSL https://rpm.nodesource.com/setup_22.x | bash - && \
    dnf install -y nodejs && \
    dnf clean all && \
    mkdir -p /root/.vscode-server/data/User

# Java Agent を最新版で取得（配置先を作成してから配置）
RUN mkdir -p /opt/java/lib && \
    wget -O /opt/java/lib/dd-java-agent.jar "https://dtdg.co/latest-java-tracer"

ENV JAVA_HOME=/var/lang
ENV PATH=$PATH:${JAVA_HOME}:${JAVA_HOME}/bin:/usr/bin

# Datadog関連のデフォルト設定（APIキー等はデプロイ時に注入）
# 公式手順に合わせて wrapper を利用
ENV AWS_LAMBDA_EXEC_WRAPPER=/opt/datadog_wrapper
ENV DD_SITE="datadoghq.com"
ENV DD_API_KEY=""
# ENV DD_SERVICE=""
# ENV DD_ENV=""

ENV BP_JVM_CDS_ENABLED=true
# Lambdaの実行ディレクトリに合わせる
WORKDIR /var/task
# 明示的にクラスパスとハンドラを指定（FunctionInvokerを確実に解決させるため）
ENV CLASSPATH="/var/task/lib/*:/opt/java/lib/*"
ENV _HANDLER="org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
ENV MAIN_CLASS="com.example.LambdaHandlerApplication"
ENV JAVA_OPTS="-Xms512m -Xmx2048m"
CMD ["org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"]
