# Android UI 功能对照计划

这份清单是 Android 版与 `/tmp/BCSFE-Python` 的逐项回归入口。字段解析必须遵循
上游 `SaveFile.load_wrapper()` 的序列；Android 编辑器只修改 `SaveDocument`，不直接
操作 UI 层的字节数组。

状态含义：

- **已对照**：已有上游生成文件或真实转移码的逐字节回归。
- **已定位**：有结构扫描/动态定位和重开校验，但缺少某个地区或版本的逐字节样本。
- **待对照**：仍需要补充真实转移码、地区或设备验收。

## 功能清单

| 分类 | Android UI 功能 | 上游字段/顺序 | Android 入口与定位 | 状态 |
|---|---|---|---|---|
| 存档管理 | 保存、导出与载入 | 原始字节、地区盐、末尾 MD5 | `editSaveManagement()`、`SaveDocument.open()` / `toBytes()` | 已对照 |
| 存档管理 | 转换地区 | 地区块、版本 marker、JP 特有字段 | `convertRegion()`、`fixed()`、地区变换结构 | 已对照 |
| 存档管理 | 转换游戏版本 | 版本 marker 与版本条件字段 | `convertGameVersion()` | 已定位 |
| 存档管理 | 上传及设备传输 | transfer receive/upload API，收到后再按完整顺序解析 | `TransferClient`、接收后 `SaveDocument.open()` | 已对照；旧 Android 设备待验 |
| 道具 | 猫罐头 | 存档头部 profile 整数 | `catFood()` / `setCatFood()` | 已对照 |
| 道具 | XP 经验值 | 存档头部 profile 整数 | `xp()` / `setXp()` | 已对照 |
| 道具 | 普通、稀有、白金与传说券 | 深部 profile marker / 可变列表 | `profileOffset()`、`legendTicketOffset()` | 已对照真实 TW 转移码 |
| 道具 | 白金碎片、NP 与统率力 | `100600`、`80000`、`80200` marker | `profileOffset()` | 已对照 |
| 道具 | 战斗道具及无限道具 | `battle_items[6]` → `new_dialogs_2` → `uil1` → 锁定/DST/date | `battleItemsOffset()` 结构扫描，按上游后缀校验 | 已对照真实 TW/JP 转移码及多份转移会话；连续写入/重开通过 |
| 道具 | 猫眼石、猫薄荷与喵力达 | `catfruit` → `fourth_forms` → `catseyes_used` → `catseyes` → `catamins` | `CatLayout` 动态列表定位 | 已对照真实 TW 转移码 |
| 道具 | 本能玉与城堡素材 | `90700` 本能玉表；`63` 城堡材料表 | `talentOrbTableOffset()`、`baseMaterialTableOffset()` | 已对照真实 TW 转移码 |
| 道具 | 活动券与宝箱 | 活动券列表、`140300` 宝箱表（数量可变） | `eventTicket*()`、`treasureChestTable()` | 已对照真实 TW/JP 转移码；宝箱数量不再固定 39 |
| 角色 | 解锁、移除与升级角色 | `cats.unlocked` → `upgrade` → `current_form`；解锁同步 `gatya_seen`/drops | `CatLayout`、`setCat*()`、批量升级；drops 按地区表 | 已对照 TW/JP 转移会话；JP `drop_chara.csv` 差异已修复 |
| 角色 | 三阶与四阶进化 | `unlocked_forms`、`current_form`、`fourth_form` | `setTrueForms()`、`setFourthForms()` | 已对照真实 TW 转移码 |
| 角色 | 本能与图鉴 | talent records、`catguide_collected` | `talentTableOffset()`、`catTalents()` | 已定位；需继续补 JP/EN 名单 |
| 角色 | 储藏库与基础升级 | storage id/type；`PowerUpHelper.reset_upgrade/upgrade_by` | `storageTableOffset()`、`applyCatBaseUpgrade()` | 已对照真实 TW 转移码 |
| 关卡 | 教学与主线 | tutorial、Story clear/progress | `clearTutorial()`、`story*()` | 已对照 |
| 关卡 | 宝物、不死生物袭击与魔界篇 | treasure、outbreak tables、Aku short 表 | `storyTreasure*()`、`findOutbreakTable()`、`akuTableOffset()` | 已对照 TW/JP 转移码；魔界其它地区待补 |
| 关卡 | 挑战与道场分数 | challenge 重复章节头、Dojo 变量表 | `StageMap.CHALLENGE`、`dojoScoreTableOffset()` | 已对照真实 TW 转移码 |
| 关卡 | 发掘关卡与强袭 | Enigma、Gauntlet 变量记录 | `enigmaBaseOffset()`、地图表扫描 | 已对照 |
| 关卡 | 传奇故事与活动 | Story/活动可变 map 表 | `editStory()`、`editEventMaps()`、`MapLayout` | 已对照真实 TW 转移码 |
| 关卡 | 真传奇、猫薄荷关卡与超兽讨伐 | Uncanny/Catamin/Behemoth map 表 | `editMapChoice()`、`StageMap` | 已对照真实 TW 转移码 |
| 关卡 | 传奇任务、塔与零传奇 | byte/short crown 与 stage 表 | `LEGEND_QUEST`、`TOWER`、`ZERO_LEGENDS` | 已对照真实 TW 转移码 |
| 加码多多 | XP、等级与助手 | Gamatoto 主表、helper 列表 | `gamatotoOffset()`、helper API | 已对照真实 TW 转移码 |
| 猫咪城开发队 | 工程师、材料与猫咪炮 | 材料表、炮台嵌套表 | `baseMaterialTableOffset()`、`cannonBase()` | 已对照真实 TW 转移码 |
| 加码多多 | 猫神社 | `90900` / `110700` marker 与对话表 | `catShrineBase()` | 已定位 |
| 账号 | 查询码与刷新令牌 | inquiry string、password refresh token | `inquiryCodeOffset()`、`passwordRefreshTokenOffset()` | 已对照真实 TW 转移码 |
| 账号 | 解除封禁与托管道具上传 | account metadata、managed item API | `showAccountOperations()`、`TransferClient` | 已定位；需设备端验证 |
| 扭蛋 | 稀有、普通与活动种子 | unit drops 后种子；marker 46 | `gatyaSeedOffset()`、`eventSeedOffset()` | 已对照真实 TW 转移码 |
| 修复 | 加码多多、猫咪城开发队与时间错误 | Gamatoto/炮台结构、date_3/timestamps | `fixGamatotoCrash()`、`fixOtotoValues()`、`fixTimeErrors()` | 已定位 |
| 修复 | 装备菜单与军官通行证 | 动态 menu unlock、Officer Pass marker | `menuUnlocksOffset()`、`officerPassCatOffset()` | 已对照真实 TW 转移码 |
| 其他 | 编队栏与抽奖活动 | lineup 列表、赌博表 | `lineup*()`、`gamblingTableOffset()` | 已定位 |
| 其他 | 游玩时间与敌人图鉴 | playtime、enemy guide list | `playTime*()`、`enemyGuide*()` | 已对照 |
| 其他 | 用户等级奖励、勋章与任务 | reward flags、medal/dictionary、8 mission dictionaries | `userRankReward*()`、`medalBaseOffset()`、`missionDictionaryOffset()` | 已定位 |
| 其他 | 黄金会员与重启礼包 | Gold Pass 可变奖励、restart marker | `goldPassBase()`、`restartPackState()` | 已对照真实 TW 转移码 |

## 当前回归顺序

1. 每次接收转移码后先 `open()`，读取所有动态列表并记录长度/计数。
2. 分别执行单项、批量、删除/解锁操作，导出后再次 `open()`。
3. 用上游同一操作生成的文件做逐字节比较；差异先检查字段顺序和可变长度，不直接平移常量。
4. 至少覆盖 TW 15.5 真实转移码、四地区模板；新增地区或游戏版本时再补真实样本。
5. 运行 `gradle test` 与 `gradle assembleDebug`，UI 改动再安装 APK 做中英文和导入/导出验收。

## 本轮重点

- 继续验证接收转移码的基础升级、猫薄荷、猫眼、Catamin、战斗道具在重新打开后不漂移。
- 将所有仍只使用模板 `offsets_N` 的入口逐一标记，能由结构定位的字段优先改为结构定位。
- 不把真实存档、转移凭据或本地 offsets 数值写入仓库。

## 2026-08-13 偏移审计记录

- 上游 `SaveFile.load()` 已用 Python 游标追踪核对：真实 873 猫转移码、861 猫 TW/JP 会话的猫咪列表、战斗道具、unit drops、种子和猫眼/猫薄荷列表起点均与 Android 动态定位一致。
- 基础升级矩阵已覆盖目标等级 1/5/10/20/30/40/60，并对 TW、JP 及多份收到的会话做全文件逐字节比较（7 份会话、49 个批量样本）；单猫升级也按上游 `PowerUpHelper.reset_upgrade()+upgrade_by()+Cat.set_upgrade(only_plus=True)` 语义复核。发现并修复 JP `drop_chara.csv` 的 24 个新增/重定位奖励映射。
- 战斗道具已覆盖 index 0/5、TW/JP、不同猫数量和多次重开；金额区、`new_dialogs_2`、锁定标志及日期后缀均保持不漂移。
- 三阶/四阶批量操作已按上游 `NyankoPictureBook` 的地区数据逐字节复核：TW、JP 收到会话的普通/强制模式均一致；JP 15.5 的 6 个 `total_forms` 差异已纳入规则表。
- 教学完成、敌人图鉴、主线宝物/通关、编队栏（含转移存档的可变编队数量/解锁字节）和任务完成状态已分别启动独立上游进程逐字节复核；修复了 `new_dialogs_2` 与编队解锁字段继续使用模板绝对偏移的问题。
- 当前仍标记为“已定位/待对照”的项目，不代表所有地区和未来版本都已认证；新增游戏版本必须重新生成上游游标矩阵。
- 2026-08-13：收到的 TW/JP 存档曾因 Dojo/方案/袭击表扫描假阳性而让后续偏移漂移；现改为校验 marker `58`、方案表与 current-outbreak 边界、marker `60` 后缀及 `chara_new_flags` 的实际表尾。`140300` 宝箱表改为读取实际长度（TW 39、JP 30），并覆盖空 `uil13` 的转移存档形态。
- 2026-08-13：复核战斗道具测试时发现一组临时脚本把“单项战斗道具”误调用为批量升级参数，造成错误的偏移误报；改用 `setBattleItem(index,value)` 后，8 份 received 存档的 index 0/5 均与上游逐字节一致。新增 received 基础升级矩阵单元测试，覆盖 8 份存档 × 7 个目标等级、导出后重开和 checksum。
