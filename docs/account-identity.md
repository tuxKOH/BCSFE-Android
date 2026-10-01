# 账号特征与存档防重复方案

## 结论

对 `issues/issue`（TW 15.5.0、873 个猫咪）执行了上游编辑器的完整可写操作矩阵（336 个 case）。再与另一份相同基础进度、不同账号的存档进行逐字节比较，除编辑器明确修改的字段和末尾 checksum 外，没有发现额外的账号标识。

当前可用的原生账号字段只有：

| 字段 | 存档位置（该样本） | 稳定性 | 用途 |
| --- | ---: | --- | --- |
| `inquiry_code` | `383379–383387` | 账号级，通常稳定 | 主账号标识 |
| `password_refresh_token` | `503015–503054` | 账号级，但服务端可能轮换 | 强辅助标识/认证凭据 |
| `energy_penalty_timestamp`（服务端称 `accountCreatedAt`） | `405223–405230` | 账号相关，但服务端流程会更新 | 辅助校验，不可单独作为 ID |

`save_data_4_hash`、游戏进度、抽奖 seed、时间字段等不是可靠账号标识。TW 存档的 `player_id` 为空。

## 不应使用的字段

- `_bcsfe:auth_token:*`：BCSFE 自定义缓存；JWT 有 `iat/exp`，有效期约一天，且样本中 payload 的 `accountCode` 可与原生 inquiry code 不一致。
- `_bcsfe:password:*`：BCSFE 自定义缓存，不是游戏原生字段。
- `_bcsfe:save_key:*`：上传签名、对象 key、policy、credential 和 security token，都是短期凭据。
- transfer code / confirmation code：一次性或短期转移凭据。
- 整份存档 SHA256、末尾 MD5 或 `save_data_4_hash`：复制存档时会相同，不能区分账号。

## 推荐的账号索引

应用或客服工具应在存档外维护账号记录，不把新的身份字段写回游戏存档：

```text
region
inquiry_code_hash
refresh_token_hashes[]
account_created_at        # 仅辅助
first_seen_at
last_seen_at
source_save_sha256        # 仅溯源，不是账号 ID
provenance_id              # 本地随机 UUID
```

其中 `inquiry_code_hash` 和 `refresh_token_hashes` 使用带应用私有盐的 HMAC-SHA-256，界面、日志和诊断报告只显示截断哈希（例如前 12 位）。原文 token 不应进入日志、崩溃报告或普通数据库字段。

匹配规则建议为：

1. `region` 必须相同；
2. `inquiry_code` 完全匹配时视为强匹配；
3. inquiry 缺失或变化时，匹配历史 refresh-token 哈希；
4. `accountCreatedAt` 只用于人工复核和服务端请求，不能单独判定账号；
5. 只有进度/内容相同而账号字段缺失时，标记为“疑似重复”，不能自动归属账号。

服务端刷新密码后可能同时更新 refresh token 和 `accountCreatedAt`，因此必须保留 token 历史，而不是只保存当前值。

## 防止“同一存档被误认成不同账号”

在导入时生成 `provenance_id`，记录原始文件 SHA256、导入时间、region 和脱敏后的账号特征；每次导出或编辑沿用同一 provenance 记录。这样可以区分“同一文件的多个编辑版本”和“账号变化”，而不会修改游戏存档格式或破坏上游兼容性。

## 能力边界

如果存档中的 `inquiry_code` 和 refresh token 都被删除、覆盖或从未保存，仅凭游戏进度无法可靠恢复原账号。此时必须依赖客服服务端记录、转移凭据或用户提供的外部证明；不存在可以从相同进度中推导出的隐藏固定 ID。
