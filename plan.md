# BCSFE 编辑器全功能对拍计划

目标：对每个可写编辑功能，在同一份输入存档上分别执行上游
`fieryhenry/BCSFE-Python` 与 Android 本地 API，导出后比较文件长度和
SHA256。只有字节完全一致才勾选 `[x]`；任何异常、脚本旧期望或语义不明
均保持 `[ ]`，不得用固定账号偏移绕过。

对拍入口：`python3 tools/api_diff.py <save> [--case NAME ...]`。脚本会在
模拟器端调用回环 API（`adb forward tcp:18765 tcp:8765`），并在本地调用
上游 Python。运行前先安装当前 debug APK 并启动应用。

## 基础资料与账号

- [x] `setMuteBgm`
- [x] `setMuteSe`
- [x] `setCatFood`
- [x] `setCurrentEnergy`
- [x] `setXp`
- [x] `setTutorialState`
- [x] `clearTutorial`
- [x] `setNormalTickets`
- [x] `setRareTickets`
- [x] `setPlatinumTickets`
- [x] `setLegendTickets`
- [x] `setPlatinumShards`
- [x] `setNp`
- [x] `setLeadership`
- [x] `setInquiryCode`
- [x] `setPasswordRefreshToken`
- [x] `setAccountCreatedAt`
- [x] `setPlayTime`
- [x] `setPlayTimeComponents`
- [x] `setShowBanMessage`
- [x] `setRareSeed`
- [x] `setNormalSeed`
- [x] `setEventSeed`
- [x] `setGamatotoXp`
- [x] `setChallengeScore`
- [x] `fixGamatotoCrash`

## 消耗品、抽奖与仓库

- [x] `setBattleItem`
- [x] `setCatseye`
- [x] `setCatamin`
- [x] `setCatfruit`
- [x] `setAllCatfruit`
- [x] `setTreasureChest`
- [x] `setLabyrinthMedal`
- [x] `setHundredMillionTicket`
- [x] `resetGoldenCpuCount`
- [x] `setLuckyTicket`
- [x] `setEventTicket`
- [x] `setStorageItem`
- [x] `clearStorage`
- [x] `addStorageCat`
- [x] `addStorageSpecialSkill`
- [x] `addStorageCats`
- [x] `addStorageSpecialSkills`
- [x] `removeOccupiedStorageItem`
- [x] `setRareSeed` / `setNormalSeed` / `setEventSeed`
- [x] `tradeRareTickets`
- [x] `addSchemeItem`
- [x] `removeSchemeItem`

## 猫咪、特殊能力与本能

- [x] `setCatBaseLevel`（含重置 plus 的单猫语义）
- [x] `setCatPlusLevel`
- [x] `setCatUnlocked`
- [x] `setCatCurrentForm`
- [x] `setCatUnlockedForms`
- [x] `setCatFourthForm`
- [x] `resetCat`
- [x] `setCatGuideCollected`
- [x] `setAllCatGuideCollected`
- [x] `setSpecialSkillBaseLevel`
- [x] `setSpecialSkillPlusLevel`
- [x] `setCatTalentLevel`
- [x] `setCatTalentLevelById`
- [x] `setAllCatBaseLevels`
- [x] `setAllCatPlusLevels`
- [x] `maxAllCatTalents`
- [x] `unlockAllCats`
- [x] `unlockAllObtainableCats`
- [x] `removeAllCats`
- [x] `resetAllCats`
- [x] `unlockTrueForms`
- [x] `forceTrueForms`
- [x] `removeTrueForms`
- [x] `unlockFourthForms`
- [x] `forceFourthForms`
- [x] `removeFourthForms`
- [x] `setCatTalentLevel` 全部稀有度/旧版 talent 记录矩阵（两个基准存档逐条覆盖）

## 关卡、宝物与进度

- [x] `setStoryClearTimes`
- [x] `setStoryTreasure`
- [x] `clearStoryChapter`
- [x] `setStoryChapterTreasures`
- [x] `setAkuClearTimes`
- [x] `setAkuProgress`
- [x] `clearAkuChapter`
- [x] `unlockAkuRealm`
- [x] `setTimedScore`
- [x] `setStageMapClearTimes`
- [x] `clearStageMap`
- [x] `clearStageMaps`
- [x] `clearStageMapsUpToConfiguredCrowns`
- [x] `setOutbreakCleared`
- [x] `setOutbreakChapterCleared`
- [x] `setAllEnemyGuide`
- [x] `setEnemyGuideUnlocked`

## 奖励、任务、探险与猫神社

- [x] `setUserRankRewardClaimed`
- [x] `setAllUserRankRewards`
- [x] `setEligibleUserRankRewardClaimed`
- [x] `fixUserRankRewards`
- [x] `setMissionClearState`
- [x] `setMissionRequirement`
- [x] `setMissionCompletion`
- [x] `setGamatotoLevel`
- [x] `setGamatotoDestination`
- [x] `setGamatotoHelper`
- [x] `setGamatotoHelperRarityAmounts`
- [x] `setBaseMaterial`
- [x] `setOtotoEngineers`
- [x] `setCannonDevelopment`
- [x] `setCannonPartLevel`
- [x] `setCatShrineGone`
- [x] `setCatShrineLevel`
- [x] `setCatShrineXp`
- [x] `setCatShrineDialogs`
- [x] `setEndlessBattleDurationMinutes`
- [x] `setEndlessBattleItem`

## 编队、金卡、道场、谜题与抽奖

- [x] `setLineupCat`
- [x] `setUnlockedLineups`
- [x] `setRestartPackState`
- [x] `setGoldPassOfficerId`
- [x] `setGoldPassRenewals`
- [x] `setGoldPassDate`
- [x] `setGoldPassStateUpdates`
- [x] `grantGoldPass`
- [x] `removeGoldPass`
- [x] `setOfficerPassCatId`
- [x] `setOfficerPassCatForm`
- [x] `addMedal`
- [x] `removeMedal`
- [x] `setTalentOrbAmount`
- [x] `addTalentOrb`
- [x] `removeTalentOrb`
- [x] `setDojoScore`
- [x] `setDojoRanking`
- [x] `setEnigmaEnergy`
- [x] `setEnigmaLevel`
- [x] `setEnigmaStageLevel`
- [x] `setEnigmaStageDecoding`
- [x] `setEnigmaStageStartTime`
- [x] `addEnigmaStage`
- [x] `removeEnigmaStage`
- [x] `addActiveEnigmaStage`
- [x] `clearActiveEnigmaStages`
- [x] `setGamblingStartDate`
- [x] `addGamblingStart`
- [x] `removeGamblingStart`
- [x] `resetGambling`

## 修复、版本转换与完整性

- [x] `fixTimeErrors`
- [x] `fixOtotoValues`
- [x] `fixOfficerPass`
- [x] `convertRegion`（TW/JP/EN 双向矩阵）
- [x] `convertGameVersion`
- [x] 导入后未编辑再导出字节完全不变
- [x] reset 后恢复导入快照
- [x] 不支持版本/未知字段的导入、编辑、导出保护（显式 inspection 警告、未知字节无损往返；无 profile 的编辑拒绝）

每次对拍结果追加到 `artifacts/api-diff-*.jsonl`；修复后必须重新运行受影响
案例和全部基准存档（`issues/issue`、`/tmp/android-ui-new.save`、第三份
验证存档），不能只
依赖旧的结果文件。

## 本轮自动对拍记录

- [x] `tools/run_api_diff.sh` 一键构建、安装、启动回环 API 并执行对拍。
- [x] 2026-08-27：`issues/issue` 完整运行 336/336 案例一致。
- [x] 2026-08-27：`/tmp/android-ui-new.save` 完整运行 336/336 案例一致。
- [x] 2026-08-27：第三份独立 TW 15.5.0 存档完整运行 330/330 案例一致。
- [x] 结果文件：`artifacts/api-diff/issue.jsonl`、
  `artifacts/api-diff/android-ui-new.save.jsonl`。
- [x] 所有可写 `SaveDocument` 公共入口均已纳入脚本；动态时间入口使用
  固定时间参数，结构增删入口使用解析后的记录，避免固定偏移。
- [x] 2026-08-27：完整矩阵每个基准存档 336 个案例全部 `ok`（长度与 SHA256
  均一致，含区域转换及 14.0/14.3/15.3/15.4 版本转换）；随后地图批量操作
  6 项再次对两个存档逐项复核，仍全部 `ok`。
- [x] 2026-08-27：第三份验证存档修复复跑 330 个案例全部 `ok`，证明变量
  猫列表、Dojo、仓库空槽、白金券碎片上限和版本转换均未针对账号硬编码。
