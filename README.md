# dicomuploader

本地 DICOM 影像批量上传工具。从本地目录递归扫描 DICOM 文件，通过 DICOM C-STORE 服务批量推送至 PACS 服务器。

## 技术栈

| 项 | 版本 |
| --- | --- |
| JDK | 1.8 |
| Spring Boot | 2.6.13 |
| dcm4che | 5.31.2 |
| 构建 | Maven（`maven-assembly-plugin` 打可执行 fat-jar） |

## 配置说明

配置位于 `src/main/resources/application.yml`：

```yaml
upload:
  dicompath: J:\测试dicom    # 待上传的本地根目录（递归扫描）
server:
  ip: 10.245.181.200         # PACS 服务器地址
  port: 11112                # PACS DICOM 端口
timer:
  cycletimeout: 5            # 预留配置，当前未使用
logging:
  file:
    path: ./logs
    name: ./logs/application.log
```

`upload.dicompath`、`server.ip`、`server.port` 为运行前必须确认的三项。

## 业务流程

程序为**一次性批处理**：启动后执行一轮上传，任务结束后进程自行退出（无 Web 端口、无常驻线程）。

```
启动 Spring Boot
     │
     ▼
Uploader.run()  ← ApplicationRunner，容器启动后自动触发
     │
     ▼
校验 upload.dicompath 是否存在且为目录 ──不满足──▶ 记录 warn 并结束
     │ 满足
     ▼
创建应用实体 AE（AE Title = STORESCU）
     │
     ▼
StoreSCU.uploadStreaming("Limage", pacsIp, pacsPort, 目录)
     │
     ├─ 1. 组建设备与连接：Device(storescu) + Connection + ApplicationEntity(STORESCU)
     ├─ 2. 预注册常用 SOP Class（CT/MR/US/DX/MG/PT/SEG/SR 等 24 类）
     │      每个 SOP Class 协商 18 种传输语法（隐式/显式 VR、JPEG 系列、
     │      JPEG2000、MPEG/HEVC、RLE、Deflated）
     ├─ 3. 发起关联协商（C-ECHO 验证上下文 + 各存储上下文），Called AET = Limage
     │
     ▼
scanAndSend(rootDir)  ← 边扫描边发送，不缓存文件清单
     │
     ▼
walkAndSend(dir) 递归遍历目录
     │  过滤条件（两者需同时满足）：
     │    · 文件名（大写后）包含 ".DCM"
     │    · 文件长度 > 25600 字节
     ▼
sendOne(file)
     │
     ├─ 读取 File Meta Information（FMI），不加载 Bulk Data
     ├─ 取 MediaStorageSOPClassUID / MediaStorageSOPInstanceUID，
     │    缺失则跳过该文件
     ├─ 取 TransferSyntaxUID，缺失则回退 ExplicitVRLittleEndian
     ▼
StoreSCU.send(file, 数据集起始偏移, cuid, iuid, ts)
     │
     ├─ 协商传输语法：优先沿用文件自身语法，其次 Explicit VR，
     │    最后 Implicit VR（依据对端关联返回的可用语法集合）
     ├─ 文件为 .xml 时走 SAXReader 解析分支
     ├─ 若无需改属性且语法一致：直接跳过 FMI，以 InputStreamDataWriter
     │    流式发送数据集，避免整文件载入内存
     └─ 否则读入数据集，按需解压后包装为 DataWriterAdapter 发送
     ▼
as.cstore(...) → 发送 C-STORE 请求
     │
     ▼
onCStoreRSP(cmd, file) 逐条处理响应
     │
     ├─ Success                          → filesSent++、totalSize 累加、记录 "."
     ├─ 警告类（元素被强制/丢弃/不匹配） → 计入成功并记录 error 日志
     └─ 其他失败状态                     → 记录 "E" 与错误详情，不计入统计
     ▼
waitForOutstandingRSP() 等待全部响应返回
     │
     ▼
输出统计：sent {filesSent} files, {totalSize/1048576} MB
     │
     ▼
close() 释放关联并等待 Socket 关闭，关闭线程池
     │
     ▼
进程退出
```

### 关键设计点

- **流式上传**：先建链再扫描，扫描到即发送，不在内存中堆积文件清单。
- **零拷贝发送**：数据集语法与协商结果一致时，用 `InputStreamDataWriter` 从文件偏移处直接读流发送，仅 `IncludeBulkData.NO` 读 FMI 获取必要元数据。
- **传输语法兜底**：对端不支持文件原始语法时自动降级为 Explicit VR / Implicit VR Little Endian。
- **容错**：单个文件解析或发送失败仅记录日志，不中断整批任务。
- **响应回调**：通过 `DimseRSPHandler` 异步接收每条 C-STORE 的响应状态，成功/失败分别统计。

## 构建与运行

```bash
# 打包（生成 target/dicom_uploader-0.0.1-SNAPSHOT.jar）
mvn clean package

# 运行
java -jar target/dicom_uploader-0.0.1-SNAPSHOT.jar
```

运行前请先确认 `application.yml` 中的上传目录、PACS 地址与端口。日志输出至 `./logs/application.log`。

打包由 `spring-boot-maven-plugin` 完成，产出为 Spring Boot 可执行 jar（依赖已内嵌），运行时只需目标机器安装 **JRE 8 及以上**。三种配置项均可通过启动参数临时覆盖，无需改配置文件：

```bash
java -jar target/dicom_uploader-0.0.1-SNAPSHOT.jar \
  --upload.dicompath=D:\dicom \
  --server.ip=10.245.181.200 \
  --server.port=11112
```

> 依赖获取说明：`dcm4che` 系列构件未发布到 Maven Central，`pom.xml` 中已声明官方仓库 `https://maven.dcm4che.org/`，首次构建需要能访问该地址（其余依赖来自 Maven Central）。

## 已知限制

- 上传目录、PACS 地址默认取自配置文件，可通过 `--upload.dicompath` 等启动参数覆盖，但未做参数校验。
- 无断点续传与失败重试，中断后重新运行会从目录头部重新扫描（已上传文件会重复推送）。
- 无并发发送，单线程顺序上传，大批量数据耗时较长。
- 未引入 `dcm4che-imageio-rle` / `dcm4che-imageio-opencv`，若 PACS 不接受文件原始传输语法、需要解压降级传输（RLE / JPEG Lossless / JPEG2000），该文件会发送失败。
- `Uploader.uploadDicom()` 与 `StoreSCU.sendFiles()` 中使用 `printStackTrace()` 输出异常，生产环境建议改为日志记录。
