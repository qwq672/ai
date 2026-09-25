# v5.0.0 源码改动

重大更新：SM8650 (Snapdragon 8 Gen 3) QAIRT 完整支持。

## 新增

### 1. libQnnHtpV75*.so（4 个文件）
- 来源：https://github.com/VIKAS9793/vendor_oneplus_giulia (OnePlus 12 vendor dump)
- 通过 GitHub LFS batch API 下载（不能用 raw，因为是大文件 LFS pointer）
- 放在 `src/main/jniLibs/arm64-v8a/`
  - `libQnnHtpV75.so` (7.1 MB)
  - `libQnnHtpV75Skel.so` (6.4 MB)
  - `libQnnHtpV75Stub.so` (376 KB)
  - `libQnnHtpV75CalculatorStub.so` (6.5 KB)

### 2. model_list.json 加入 nith3n 的 SM8650 QAIRT 模型
- 来源：https://hf-mirror.com/nith3n/qwen3-4b-instruct-2507-qairt-sm8650-w4a16
- W4A16 量化（4-bit 权重 + 16-bit 激活）
- chipset=SM8650, hub=HFMIRROR
- 4 个 .bin 分片 + genie_config.json + tokenizer

### 3. ModelManagerActivity 多文件下载
- 新增 `startHfMirrorMultiFileDownload(model)`：
  * 用 HF API `GET /api/models/{repo}` 列出所有文件
  * 全部下载到 `filesDir/local_models/<model_id>/`
  * 断点续传：已存在文件跳过
  * 进度显示 `(N/M) filename`
  * 下载完成后用 `ModelPullInput(hub=LOCALFS, local_path=目录)` 注册到 SDK
- 改下载源选择逻辑：QAIRT 模型也支持 hf-mirror 下载
