@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.composemc.forge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.composemc.host.UiBinding
import dev.composemc.forge.config.ConfigEditor
import dev.composemc.forge.config.ConfigEditor.*
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.input.OreTextField
import dev.composemc.ui.ore.layout.OreScreen
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.navigation.OreListItem
import dev.composemc.ui.ore.navigation.OreTab
import dev.composemc.ui.ore.overlay.OreDialog
import dev.composemc.ui.ore.scroll.OreScrollbar
import dev.composemc.ui.ore.selection.OreSwitch
import dev.composemc.ui.ore.theme.OreTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.common.ForgeConfigSpec
import java.util.Locale

private data class ConfigField(val source: EntryView, val title: String, val comment: String, val choices: Map<String,String>)
private data class ConfigFile(val source: FileView, val title: String, val fields: List<ConfigField>)
private data class ConfigReply(val id: Long = 0, val result: Result = Result.OK)
private data class ConfigView(val title: String, val files: List<ConfigFile>, val labels: Map<String,String>, val changes: Int,
    val reply: ConfigReply, val saved: Boolean, val restart: RestartType)
private data class ConfigAction(val kind: String, val file: String = "", val entry: String = "", val input: Input = Input.scalar(""), val id: Long = 0)
private class ConfigUiState {
    var file by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var edit by mutableStateOf<ConfigField?>(null)
    var text by mutableStateOf("")
    val elements = mutableStateListOf<String>()
    var pending by mutableLongStateOf(0)
    var error by mutableStateOf<Result?>(null)
    var confirmClose by mutableStateOf(false)
    var sequence = 0L
}
private class ConfigController(val mod: ModContainer) : AutoCloseable {
    val editor = ConfigEditor(mod.modId)
    val local = ConfigUiState()
    private var language: Language? = null
    private var labels = emptyMap<String,String>()
    private val sources = mutableMapOf<String,List<EntryView>>()
    private val fields = mutableMapOf<String,List<ConfigField>>()
    private var reply = ConfigReply()
    private var saved = false
    private var restart = RestartType.NONE
    private var closed = false
    val ui = UiBinding<ConfigView,ConfigAction>(snapshot())
    fun tick(close: () -> Unit) {
        ui.drainActions { action ->
            val result = when(action.kind) {
                "stage" -> editor.stage(action.file,action.entry,action.input)
                "default" -> editor.reset(action.file,action.entry)
                "reload" -> editor.reload(action.file)
                "save" -> editor.save(action.file).let { result ->
                    if(result.result()==Result.OK) { saved=true;restart=restart.with(result.restart()) }; result.result()
                }
                "close" -> { if(editor.changes()==0)close(); Result.OK }
                "discardClose" -> { editor.discardAll(); close(); Result.OK }
                else -> Result.UNKNOWN
            }
            if(action.kind!="save")saved=false
            reply=ConfigReply(action.id,result)
        }
        if(!closed)ui.update(snapshot())
    }
    private fun snapshot(): ConfigView {
        fun text(key:String,fallback:String=key)=if(Language.getInstance().has(key))Component.translatable(key).string else fallback
        val lang=Language.getInstance()
        if(lang!==language) {
            labels=listOf("title","search","empty","save","reload","done","edit","view","apply","cancel","default","add","remove","up","down","changed",
                "discard_title","discard_text","discard","saved","items","restart_WORLD","restart_GAME","type_CLIENT","type_COMMON","type_SERVER","type_STARTUP",
                "access_NOT_LOADED","access_REMOTE_SERVER","access_LAN_SERVER","access_RELOADED","access_UNSUPPORTED","result_INVALID","result_READ_ONLY",
                "result_CONFLICT","result_SAVE_FAILED","result_UNKNOWN","unsupported","shared_server").associateWith { text("composemc.config.$it") }
            sources.clear();fields.clear();language=lang
        }
        val files=editor.snapshot().map { file ->
            if(sources[file.id()]!==file.entries()) {
                sources[file.id()]=file.entries()
                fields[file.id()]=file.entries().map { entry ->
                    val key=entry.translationKey() ?: "${mod.modId}.configuration.${entry.path().joinToString(".")}"
                    val title=text(key,entry.path().joinToString(" / ") { humanize(it) })
                    ConfigField(entry,title,text("$key.tooltip",entry.comment()),entry.choices().associateWith {
                        text("$key.${it.lowercase(Locale.ROOT)}",text("${mod.modId}.configuration.option.${it.lowercase(Locale.ROOT)}",humanize(it)))
                    })
                }
            }
            ConfigFile(file,labels.getValue("type_${file.type().name}"),fields.getValue(file.id()))
        }
        return ConfigView(mod.modInfo.displayName+" · "+labels.getValue("title"),files,labels,editor.changes(),reply,saved,restart)
    }
    override fun close(){closed=true;ui.close()}
    private fun humanize(value:String)=value.replace(Regex("([a-z0-9])([A-Z])"),"$1 $2").replace('_',' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
}

/** Register with ConfigScreenHandler.ConfigScreenFactory. Values are staged until Save; only loader-owned, locally editable files are changed. */
open class ComposeConfigScreen private constructor(mod: ModContainer, private val parent: Screen,
    private val controller: ConfigController) :
    ComposeScreen(Component.literal(mod.modInfo.displayName),content={ ConfigContent(controller.ui,controller.local) }) {
    constructor(mod: ModContainer,parent: Screen):this(mod,parent,ConfigController(mod))
    val editor: ConfigEditor get()=controller.editor
    override fun tick(){super.tick();controller.tick { Minecraft.getInstance().setScreen(parent) }}
    override fun onClose(){
        if(controller.editor.changes()>0) controller.local.confirmClose=true
        else Minecraft.getInstance().setScreen(parent)
    }
    override fun removed(){try{controller.close()}finally{super.removed()}}
}

@Composable
private fun ConfigContent(binding: UiBinding<ConfigView,ConfigAction>,local: ConfigUiState) {
    val state=binding.value;val labels=state.labels
    val dialogHeight=with(LocalDensity.current){(LocalWindowInfo.current.containerSize.height.toDp()-105.dp).coerceIn(24.dp,160.dp)}
    val file=state.files.firstOrNull { it.source.id()==local.file } ?: state.files.firstOrNull()
    val editable=file?.source?.access()==Access.EDITABLE
    val list=rememberLazyListState()
    val rows=remember(file?.fields,local.query){val term=local.query.trim().lowercase(Locale.ROOT);file?.fields.orEmpty().filter { term.isEmpty()||term in (it.title+" "+it.comment+" "+it.source.id()).lowercase(Locale.ROOT) }}
    fun send(kind:String,entry:String="",input:Input=Input.scalar(""),await:Boolean=false){
        val id=++local.sequence
        if(binding.send(ConfigAction(kind,file?.source?.id().orEmpty(),entry,input,id))&&await)local.pending=id
    }
    fun close(){if(state.changes>0)local.confirmClose=true else send("close")}
    LaunchedEffect(file?.source?.id(),local.query){list.scrollToItem(0)}
    LaunchedEffect(state.reply){if(local.pending>0&&state.reply.id==local.pending){
        local.pending=0
        if(state.reply.result==Result.OK){local.edit=null;local.error=null}else local.error=state.reply.result
    }}
    OreScreen(state.title,maxWidth=400.dp,maxHeight=450.dp,onClose={close()},closeLabel=labels.getValue("done"),footer={
        OreButton(labels.getValue("reload"),{send("reload")},Modifier.weight(1f),enabled=file!=null,style=OreButtonStyle.Secondary)
        OreButton(labels.getValue("save"),{send("save")},Modifier.weight(1.4f),enabled=editable&&(file?.source?.changes()?:0)>0)
        OreButton(labels.getValue("done"),{close()},Modifier.weight(1f),style=OreButtonStyle.Secondary)
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            state.files.forEach { candidate -> OreTab(candidate.title+(if(state.files.count { it.source.type()==candidate.source.type() }>1)" · "+candidate.source.id() else "")+(if(candidate.source.changes()>0)" *" else ""),candidate==file,{
                local.file=candidate.source.id();local.query="";local.edit=null
            }) }
        }
        file?.let {
            OreText(it.source.id(),style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
            if(!editable)OreText(labels.getValue("access_${it.source.access().name}"),style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
            else if(it.source.sharedServerFile())OreText(labels.getValue("shared_server"),style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
        }
        OreTextField(local.query,{local.query=it},Modifier.fillMaxWidth(),placeholder=labels.getValue("search"))
        if(state.reply.result!=Result.OK)OreText(labels.getValue("result_${state.reply.result.name}"),color=OreTheme.colors.danger,style=OreTheme.typography.caption)
        else if(state.saved)OreText(labels.getValue("saved"),style=OreTheme.typography.caption)
        if(state.restart!=RestartType.NONE)OreText(labels.getValue("restart_${state.restart.name}"),style=OreTheme.typography.caption)
        if(state.changes>0)OreText("${labels.getValue("changed")}: ${state.changes}",style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end=7.dp),state=list,verticalArrangement=Arrangement.spacedBy(4.dp)){
                items(rows,key={it.source.id()}) { row ->
                    val entry=row.source
                    OreSurface(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.5.dp)) {
                                OreText(row.title+(if(entry.changed())" *" else ""))
                                OreText(when(entry.kind()){
                                    Kind.BOOLEAN->row.comment.lineSequence().firstOrNull().orEmpty()
                                    Kind.LIST->"${entry.value().elements().size} ${labels.getValue("items") }"
                                    Kind.UNSUPPORTED->labels.getValue("unsupported")
                                    else->row.choices[entry.value().text()]?:entry.value().text().replace('\n',' ').take(100)
                                },style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
                            }
                            if(entry.kind()==Kind.BOOLEAN) {
                                OreSwitch(entry.value().text()=="true",{send("stage",entry.id(),Input.scalar(it.toString()))},enabled=editable)
                                OreButton(labels.getValue("default"),{send("default",entry.id())},enabled=editable&&entry.value()!=entry.defaults(),style=OreButtonStyle.Quiet)
                            }
                            else OreButton(labels.getValue(if(editable)"edit" else "view"),{
                                local.edit=row;local.text=entry.value().text();local.elements.clear();local.elements.addAll(entry.value().elements());local.error=null
                            },style=OreButtonStyle.Secondary)
                        }
                    }
                }
            }
            OreScrollbar(list,Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            if(rows.isEmpty())OreText(labels.getValue("empty"),Modifier.align(Alignment.Center),color=OreTheme.colors.mutedText)
        }
    }
    local.edit?.let { row ->
        val entry=row.source
        OreDialog(row.title,{local.edit=null},closeLabel=labels.getValue("cancel"),buttons={
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            if(editable&&entry.kind()!=Kind.UNSUPPORTED) {
                OreButton(labels.getValue("apply"),{send("stage",entry.id(),if(entry.kind()==Kind.LIST)Input.list(local.elements.toList())else Input.scalar(local.text),true)},Modifier.weight(1.2f),enabled=local.pending==0L)
                OreButton(labels.getValue("default"),{send("default",entry.id(),await=true)},Modifier.weight(1f),style=OreButtonStyle.Secondary)
            }
            OreButton(labels.getValue("cancel"),{local.edit=null},Modifier.weight(1f),style=OreButtonStyle.Secondary)
            }
        }) {
            Column(Modifier.heightIn(max=dialogHeight),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(row.comment.isNotBlank())OreText(row.comment,Modifier.heightIn(max=40.dp).verticalScroll(rememberScrollState()),style=OreTheme.typography.caption,color=OreTheme.colors.mutedText)
                if(entry.range().isNotBlank())OreText(entry.range(),style=OreTheme.typography.caption)
                if(entry.restart()!=RestartType.NONE)OreText(labels.getValue("restart_${entry.restart().name}"),style=OreTheme.typography.caption)
                when(entry.kind()) {
                    Kind.ENUM->LazyColumn(Modifier.heightIn(max=90.dp).weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        items(row.choices.entries.toList(),key={it.key}) { (value,label) -> OreListItem(local.text==value,{local.text=value},Modifier.fillMaxWidth(),enabled=editable){OreText(label)} }
                    }
                    Kind.LIST->{
                        LazyColumn(Modifier.heightIn(max=90.dp).weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(3.dp)) { itemsIndexed(local.elements) { index,text ->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically){
                                OreTextField(text,{next->if(next.length<=1_048_576)local.elements[index]=next},Modifier.weight(1f),readOnly=!editable||local.pending>0)
                                OreButton("↑",{java.util.Collections.swap(local.elements,index,index-1)},Modifier.width(18.dp).semantics { contentDescription=labels.getValue("up") },enabled=editable&&index>0,style=OreButtonStyle.Secondary)
                                OreButton("↓",{java.util.Collections.swap(local.elements,index,index+1)},Modifier.width(18.dp).semantics { contentDescription=labels.getValue("down") },enabled=editable&&index<local.elements.lastIndex,style=OreButtonStyle.Secondary)
                                OreButton("−",{local.elements.removeAt(index)},Modifier.width(18.dp).semantics { contentDescription=labels.getValue("remove") },enabled=editable,style=OreButtonStyle.Secondary)
                            }
                        } }
                        OreButton(labels.getValue("add"),{if(local.elements.size<100_000)local.elements.add(entry.newElement())},Modifier.fillMaxWidth(),enabled=editable&&entry.canAdd(),style=OreButtonStyle.Secondary)
                    }
                    Kind.UNSUPPORTED->OreText(labels.getValue("unsupported"),color=OreTheme.colors.mutedText)
                    else->OreTextField(local.text,{if(it.length<=1_048_576)local.text=it},Modifier.fillMaxWidth(),singleLine=entry.kind()!=Kind.STRING,readOnly=!editable,isError=local.error!=null)
                }
                local.error?.let { OreText(labels.getValue("result_${it.name}"),color=OreTheme.colors.danger,style=OreTheme.typography.caption) }
            }
        }
    }
    if(local.confirmClose)OreDialog(labels.getValue("discard_title"),{local.confirmClose=false},closeLabel=labels.getValue("cancel"),buttons={
        OreButton(labels.getValue("discard"),{send("discardClose")},Modifier.fillMaxWidth(),style=OreButtonStyle.Destructive)
        OreButton(labels.getValue("cancel"),{local.confirmClose=false},Modifier.fillMaxWidth(),style=OreButtonStyle.Secondary)
    }) { OreText(labels.getValue("discard_text")) }
}
