package org.inkdock.reader

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** In-app downloads are explicit; leaving this screen cancels outstanding reads within the timeout. */
class KokoroSetupActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val variant get()=KokoroRepository.selected(this)
    private val store get()=KokoroRepository.get(this)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var download: Button
    private lateinit var delete: Button
    private var lastProgress=0L
    private var busy=false
    private var activeDownload:KokoroModelStore?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        fun text(value:String)=TextView(this).also {it.text=value;it.textSize=17f;it.setPadding(0,16,0,16);body.addView(it)}
        fun button(label:String,action:()->Unit)=Button(this).also {it.text=label;it.setOnClickListener{action()};body.addView(it)}
        text("Kokoro 中文离线包 · 实验")
        text("进入此页面会停止朗读并释放正在使用的Kokoro模型。真正下载到 InkDock 内，在设备上合成，无需安装其它 TTS 应用。此实验版使用尚未正式发布的去 eSpeak 依赖运行库。只支持中文词典路径；英文混排、数字与多音字仍需实际试听。\n\n高音质float约381.8 MB（优先），省空间int8约170.4 MB。云端测试float约516–529 MiB内存，int8约316–338 MiB；手机可能更高、速度不同。用户对本次同声线样例试听更偏好float，不保证所有音色和设备无噪声。分别校验8个文件，不下载eSpeak数据。建议Wi-Fi下载。")
        for (choice in KokoroManifest.variants) button("选择"+choice.label) {
            try { check(!busy) { "请等待当前操作结束" }; KokoroRepository.select(this,choice); update("已选择"+choice.label+"；不会自动下载。") }
            catch(e:Exception){update(e.message?:"无法切换")}
        }
        text("两包独立保存，可分别删除；同时安装约552.2 MB，至少另留64 MiB空间。为保证独立删除安全，公共文件目前不共享存储。切换前必须释放旧引擎，最多只加载一个模型。")
        status=text("")
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {max=1000};body.addView(progress)
        download=button("下载中文离线包") {
            AlertDialog.Builder(this).setTitle("下载到此设备")
                .setMessage("将从维护者的 Hugging Face 仓库下载${String.format(java.util.Locale.ROOT,"%.1f",variant.bytes/1_000_000.0)} MB的${variant.id}模型和中文数据，保存于InkDock私有目录。不会发送正文，可能产生移动数据费用。模型为Apache-2.0，相关开源声明见应用内许可文件。继续下载？")
                .setNegativeButton("取消",null).setPositiveButton("开始下载") {_,_-> startDownload()}.show()
        }
        button("取消下载") {activeDownload?.cancelDownload();status.text="正在取消，当前网络连接结束后停止；已校验文件保留供重试。"}
        delete=button("删除当前所选包和临时文件") {
            if(busy)return@button
            AlertDialog.Builder(this).setTitle("删除离线模型？").setMessage("会移除当前所选${variant.id}包及其临时文件，不会删除另一种包；以后需要重新下载。")
                .setNegativeButton("保留",null).setPositiveButton("删除") {_,_->val target=store;busy=true;worker.execute{try{target.delete();update("模型已删除。")}catch(e:Exception){update(e.message?:"删除失败")}finally{runOnUiThread{busy=false;update("")}}}}.show()
        }
        text("下载完成后返回声音设置，选择“Kokoro 中文（内置实验）”，切换引擎，再选音色并保存试听。音色编号对应这个固定版本的说话人，不是情感强度。\n\n离开此页面会取消正在进行的下载，已完整校验的文件可供下次重试。取消不会留下可误用的半个模型。")
        button("查看开源许可") {
            val content=buildString {
                append(assets.open("licenses/Kokoro-sources.txt").bufferedReader().use{it.readText()})
                append("\n\n");append(assets.open("licenses/Kokoro-model-APACHE-2.0.txt").bufferedReader().use{it.readText()})
                append(KokoroLicenseText.collect("licenses/kokoro-native", { assets.list(it).orEmpty().toList() }, { assets.open(it).bufferedReader().use { reader->reader.readText() } }))
            }
            AlertDialog.Builder(this).setTitle("模型与运行库许可").setMessage(content).setPositiveButton("关闭",null).show()
        }
        button("返回声音设置") {finish()}
        val scroll=ScrollView(this).apply{addView(body)}
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        update("")
    }
    private fun startDownload() {
        if(busy)return
        if(!KokoroRepository.runtimeAvailable()) {update("此构建缺少Kokoro运行库，不能启用模型下载。请使用包含已验证原生库的实验构建。");return}
        val target=store
        val token=try { target.prepareDownload() } catch(e:Exception) {update(e.message?:"无法开始下载");return}
        busy=true;activeDownload=target
        download.isEnabled=false;delete.isEnabled=false
        var result=""
        worker.execute {
            try { target.download(token) { p ->
                val now=System.currentTimeMillis()
                if(now-lastProgress>150 || p.bytes==p.total){lastProgress=now;runOnUiThread{if(!isDestroyed){progress.progress=(p.bytes*1000/p.total).toInt();status.text="${p.file} · ${p.bytes/1_000_000} / ${p.total/1_000_000} MB，完成后校验"}}}
            };result="模型下载并校验完成。返回声音设置可选择Kokoro并试听。" }
            catch(e:Exception){result=e.message?:"下载失败，请重试。"}
            finally {runOnUiThread{busy=false;activeDownload=null;update(result)}}
        }
    }
    private fun update(message:String)=runOnUiThread {
        if(!isDestroyed){
            val available=KokoroRepository.runtimeAvailable()
            status.text=variant.label+"\n占用 ${store.storedBytes()/1_000_000} MB（含未完成 ${store.stagedBytes()/1_000_000} MB）\n"+if(message.isNotBlank())message else if(!available)"此构建未包含已核验的原生运行库，暂不可用。" else if(store.isReady())"已安装 · ${store.storedBytes()/1_000_000} MB" else "尚未下载；点击下载才会联网。"
            download.isEnabled=available&&!busy&&!store.isDownloading();delete.isEnabled=!busy&&!store.isDownloading()
        }
    }
    override fun onStop(){activeDownload?.cancelDownload();super.onStop()}
    override fun onDestroy(){activeDownload?.cancelDownload();worker.shutdown();super.onDestroy()}
}
