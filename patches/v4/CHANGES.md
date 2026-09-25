# v4.0.0 源码改动

相对 v3.0.0 的 UI 重构 + 模型分类 + 设置扩展。

## 主要改动

### UI 重构
- 应用名改为「GenieX 聊天」
- 全部 strings.xml 翻译为简体中文
- 主聊天界面采用 ChatGPT 风格：顶部 Material3 Toolbar + 三点菜单 + 底部圆角输入框 + 圆形发送按钮
- 用户消息右对齐绿色气泡，AI 消息左对齐白色卡片
- 移除原 Spinner + 横向按钮栏，所有操作收纳到顶栏菜单
- 性能数据 footer 仍显示在每条 AI 消息下方

### 模型管理 TabLayout
- TabLayout + ViewPager2 两个 Tab：GGUF 通用模型 / QAIRT NPU 专属
- GGUF Tab 不显示设备徽章（通用）
- QAIRT Tab 不兼容模型灰显（alpha 0.5）+ 标红章「不兼容本机」，但保留显示
- 兼容模型显示绿点徽章

### 设置扩展
- CPU 线程模式：Auto / Custom 切换
- 下载源选择：Auto / HuggingFace / hf-mirror.com
- 聊天历史持久化开关 + 立即清空
- 清除所有已下载模型按钮
- 磁盘占用查看
- 应用版本（点击查看构建详情）

### 新增文件
- `utils/ChatHistory.kt`：聊天历史持久化
- `res/menu/menu_main.xml`：顶部菜单
- `res/layout/fragment_model_list.xml`：Tab 内容
- `res/drawable/{bg_input_rounded,bg_send_button,bg_user_bubble,bg_ai_bubble,bg_card_rounded,bg_incompat_badge,ic_back}.xml`

### 修改文件
- `themes.xml`：升级为 Material3 主题 + 品牌色（#10A37F）
- `colors.xml`：新增品牌色系
- `strings.xml`：全部中文化 + 新增字段标签
- `activity_main.xml`：完全重构
- `activity_model_manager.xml`：加 TabLayout + ViewPager2
- `item_ai_message.xml` / `item_user_message.xml`：新气泡样式
- `item_model_manager.xml`：加 incompat_badge
- `MainActivity.kt`：移除 Spinner / 按钮栏，加 Toolbar 菜单 + 历史恢复
- `ModelManagerActivity.kt`：改 TabLayout + Fragment 结构
- `SettingsActivity.kt`：新增多项设置逻辑
- `Settings.kt`：新增 cpuThreadsMode / downloadSource / chatHistoryEnabled
- `build.gradle`：开启 buildConfig + 加 viewpager2 依赖
