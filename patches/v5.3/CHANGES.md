# v5.3.0 源码改动

修复菜单按钮全部失效。

## 根因
- MainActivity 继承 `FragmentActivity`，但用 MaterialToolbar
- FragmentActivity 不支持 Toolbar 菜单回调（onCreateOptionsMenu 等需 AppCompatActivity）
- 菜单点击事件不触发，"按钮全都没用"

## 修复
1. MainActivity 改继承 `androidx.appcompat.app.AppCompatActivity`
2. 添加 `onCreateOptionsMenu` 显式 inflate menu
3. 添加 `onOptionsItemSelected` 处理菜单点击
4. `initView` 中调 `setSupportActionBar(toolbar)` 让 Toolbar 接管 ActionBar
5. 每步独立 try-catch + AppLogger 日志
6. menu 点击事件加 log 方便诊断
