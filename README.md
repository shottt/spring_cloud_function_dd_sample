# spring_cloud_function_dd_sample

Spring Cloud Function（Java 21）＋Datadog APM 対応の Lambda サンプルです。  
コンテナイメージ運用と ZIP 配布の両方に対応しています。

## 主なファイル
- `src/main/java/com/example/LambdaHandlerApplication.java` : 関数本体（手動スパン付きと自動計測の2種類を提供）
- `build.gradle` : Gradle ビルド設定
- `Dockerfile` : マルチステージ。builder で Gradle ビルドし、runner に成果物と Datadog を同梱

## ビルド＆プッシュ（コンテナイメージ）
※ Lambda を arm64 で使う想定。x86_64 の場合は `--platform linux/amd64` を付けてください。
```bash
DOCKER_BUILDKIT=0 docker build -t <ECR-URI>:latest .
docker push <ECR-URI>:latest
```
（Dockerfile 内で `gradle clean jar copyRuntimeLibs` を実行するため、事前にローカルでビルドする必要はありません）

## ZIP 配布で Lambda にアップする場合
1. ラッパーがない場合は作成（Gradleがインストール済みなら wrapper を省略して `gradle` でも可）
   ```bash
   gradle wrapper --gradle-version 8.10.2 --distribution-type=bin
   chmod +x gradlew
   ```
2. ZIP を生成
   ```bash
   gradle clean lambdaZip
   ```
   生成物: `build/distributions/spring_cloud_function_dd_sample-0.1.0-lambda.zip`
3. Lambda にコード更新（ZIP）
   ```bash
   aws lambda update-function-code \
     --function-name <FUNCTION_NAME> \
     --region <REGION> \
     --zip-file fileb://build/distributions/spring_cloud_function_dd_sample-0.1.0-lambda.zip
   ```
3. Lambda 設定
   - ランタイム: Java 21
   - ハンドラ: `org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest`
   - 環境変数: Datadog (例) `AWS_LAMBDA_EXEC_WRAPPER=/opt/datadog_wrapper`, `DD_API_KEY`, `DD_SITE` など
   - （レイヤー運用する場合は dd-trace-java と Datadog-Extension をアーキテクチャに合わせて追加）

## 関数の実行方法（手動確認）
AWS CLI で直接 invoke します。`--cli-binary-format raw-in-base64-out` を付けて JSON を渡してください。
```bash
aws lambda invoke \
  --function-name <FUNCTION_NAME> \
  --region <REGION> \
  --cli-binary-format raw-in-base64-out \
  --payload '{"id":1}' \
  /tmp/out.json

cat /tmp/out.json
```
レスポンス例: `{"id":1,"found":true,"item":{"id":1,"name":"alpha","status":"active"}}`

## Lambda 設定（コンテナイメージ）
- イメージURI: 上記でプッシュした ECR イメージ
- ハンドラ: `org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest`
- 環境変数（例）
  - `AWS_LAMBDA_EXEC_WRAPPER=/opt/datadog_wrapper`
  - `DD_SITE=datadoghq.com`
  - `DD_API_KEY`（本番では Secrets 等で注入）
  - 必要に応じて `DD_SERVICE` `DD_ENV` など

## Datadog
- Datadog Java Agent と Extension は Dockerfile で `/opt` に同梱済み。
- アーキテクチャは Lambda に合わせて `arm64` を使用。


## 動作確認のヒント
- タイムアウト/メモリは十分に確保（例: 1024MB / 15s 以上）
- イメージに依存JARと本体JARが含まれているかは `docker run --rm --entrypoint "" <image> sh -c 'ls /var/task/lib'` で確認できます。
