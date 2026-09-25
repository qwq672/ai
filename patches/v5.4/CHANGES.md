# v5.4.0 源码改动

一行修复 v5.3 的崩溃：Button → ImageButton。

## 根因
activity_main.xml 中 btn_add_image / btn_send 是 `<ImageButton>`，但 Kotlin 用 `Button` 类型 findViewById，AppCompat 把它包成 `AppCompatImageButton`，强转 `Button` 失败：
```
java.lang.ClassCastException: androidx.appcompat.widget.AppCompatImageButton cannot be cast to android.widget.Button
    at com.geniex.demo.MainActivity.initView(MainActivity.kt:262)
```

被 try-catch 吞了，btnAddImage 没初始化，setListeners 第一行 `btnAddImage.setOnClickListener` 又抛 `lateinit property btnAddImage has not been initialized`。

## 修复
```diff
- private lateinit var btnSend: Button
- private lateinit var btnAddImage: Button
+ private lateinit var btnSend: ImageButton
+ private lateinit var btnAddImage: ImageButton
```
