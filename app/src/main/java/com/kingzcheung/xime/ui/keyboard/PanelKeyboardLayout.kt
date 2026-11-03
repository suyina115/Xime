package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.PanelLayoutConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.isTablet
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode

/**
 * 面板键盘（数字 / 常用符号）——yaml 配置驱动的统一渲染入口。
 *
 * 布局来自 `keyboard.<section>`（布局缺失时由内置 yaml 兜底，正常恒存在）：
 * - rows 模式（常用符号）：[PanelRow] 逐行渲染，横屏分体开启时走统一左右拆分；
 * - columns 模式（数字键盘）：三列布局（键柱 0.8 / 数字区 3.4 / 功能列 0.8）。
 *
 * 与旧硬编码面板的行为对齐：
 * - 按键统一发旧键值（"abc"/"number"/"symbol"/"emoji"/数字/运算符…），上层拦截链不变；
 * - 常用符号面板中英共用一段（无 En 键）：键位与字形只有一套，不受引擎中英态影响；
 * - 数字键盘的返回键位置随 [backKeyOnLeft] 镜像（left 末键 ↔ rows 末行首键）。
 */
@Composable
fun PanelKeyboardLayout(
    section: String,
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    isAsciiMode: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    specialKeyTextColor: Color = Color.White,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    /** 数字键盘进入位置记忆：true = 返回键在左下角（与进入的键盘位置对齐）。 */
    backKeyOnLeft: Boolean = false,
    /** 横屏分体（跟随分体设置）：rows 模式走统一左右拆分；columns 模式维持整块。 */
    landscapeSplit: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val useSplit = isLandscape && landscapeSplit
    val suppressCursorMove = LocalSuppressCursorMove.current
    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val context = LocalContext.current
    // 数字键盘运算符栏：自定义符号（持久化）+ 自由编辑态 + 原符号面板选择态
    var operatorSymbols by remember { mutableStateOf(SettingsPreferences.getNumberOperatorSymbols(context)) }
    var editingOperators by remember { mutableStateOf(false) }
    var showSymbolPicker by remember { mutableStateOf(false) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        swipeBubble.update(
            state,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top,
            )
        )
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width,
    )

    val layout = KeysConfigHelper.getPanelLayout(section)
    // 值键字号：数字键盘 = KeyButton 默认（与旧版 keyFontSize Unspecified 一致）；常用符号 = 20sp
    val valueFontSize: TextUnit = if (section == "number") TextUnit.Unspecified else 20.sp
    val env = QwertyRowEnv(
        isAsciiMode = isAsciiMode,
        isShifted = false,
        shiftMode = ShiftMode.OFF,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        keyTextColor = keyTextColor,
        specialKeyTextColor = specialKeyTextColor,
        keyboardBackgroundColor = keyboardBackgroundColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPress = onKeyPress,
        onKeyPressDown = onKeyPressDown,
        onKeyRelease = null,
        onCommitText = callbacks.onCommitText,
        onGestureAction = buildUiGestureHandler(viewModel, callbacks, uiState, requestVoice = null, onKeyPress = onKeyPress),
        onVoiceModeChange = null,
        isSttEnabled = false,
        isVoiceMode = false,
        isVoiceSticky = false,
        schemaName = "",
        enterKeyText = tr(R.string.s_queding),
        suppressCursorMove = suppressCursorMove,
        processSwipeState = { state, bounds -> processSwipeState(state, bounds) },
        configVersion = 0,
        landscape = false,
        fontSize = valueFontSize,
        ctrlFontSize = if (section == "number") null else 14.sp,
        section = section,
    )

    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        // 键面提示（退格的「清空」等）与主键盘同一档：横屏/平板画到键角，平板放大加深
        LocalSwipeHintCorner provides swipeHintCornerFor(isLandscape, isTablet()),
        LocalHintExtraScale provides hintExtraScaleFor(isTablet()),
    ) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            },
    ) {
        CompositionLocalProvider(
            LocalKeyVisualPadding provides PaddingValues(
                horizontal = keySpacingX ?: 2.dp,
                vertical = keySpacingY ?: 4.dp,
            )
        ) {
            when {
                layout == null -> Box(Modifier.fillMaxSize())
                layout.columns -> ColumnsPanelContent(
                    layout = mirrorColumnsForEntryPosition(layout, backKeyOnLeft),
                    env = env,
                    operatorSymbols = operatorSymbols,
                    editingOperators = editingOperators,
                    onOperatorDelete = { symbol ->
                        // 至少保留一个符号，避免运算符栏被删空
                        val updated = operatorSymbols - symbol
                        if (updated.isNotEmpty()) {
                            operatorSymbols = updated
                            SettingsPreferences.setNumberOperatorSymbols(context, updated)
                        }
                    },
                    onOperatorAdd = { showSymbolPicker = true },
                    onOperatorEditToggle = { editingOperators = !editingOperators },
                    // 外围留白与九键拼音键盘（T9KeyboardLayout）对齐：竖屏 4dp 两侧 + 8dp 底部
                    // （平板 16dp，与主键盘/符号面板一致），横屏 2dp 上下 + 50dp 左右。
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(panelOuterPadding(isLandscape, isTablet())),
                )
                useSplit -> RowsPanelSplitContent(layout.rows, env, Modifier.fillMaxSize())
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 平板走 10dp 两侧（键面距边 16dp），手机维持 4dp
                        .padding(panelOuterPadding(isLandscape = false, isTablet = isTablet())),
                ) {
                    layout.rows.forEach { row ->
                        PanelRow(row, env, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            // 自由编辑新增符号：复用键盘原有的全量符号面板（样式/分类/图标完全一致）
            if (section == "number" && showSymbolPicker) {
                SymbolKeyboardLayout(
                    onSelect = { symbol ->
                        if (symbol.isNotBlank() && symbol != "delete" && symbol !in operatorSymbols) {
                            val updated = operatorSymbols + symbol
                            operatorSymbols = updated
                            SettingsPreferences.setNumberOperatorSymbols(context, updated)
                        }
                    },
                    onBack = { showSymbolPicker = false },
                    backgroundColor = keyboardBackgroundColor,
                    textColor = keyTextColor,
                    // 选中分类 tab 的底色：用功能键底色（浅底），文字 keyTextColor（深字）才有对比；
                    // 误传 specialKeyTextColor（深字色）会让选中 tab 变深色块、文字不可见
                    accentColor = specialKeyBackgroundColor,
                    keyBgColor = keyBackgroundColor,
                    specialKeyBgColor = specialKeyBackgroundColor,
                    specialKeyTextColor = specialKeyTextColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    }
}

/** 键盘区两侧外围留白（dp）：手机 4、平板 16（键面距屏幕边 20dp，与平板 26 键同档）。 */
internal const val PHONE_KEYBOARD_SIDE_PADDING_DP = 4
internal const val TABLET_KEYBOARD_SIDE_PADDING_DP = 16

/** 键盘区两侧外围留白（dp，竖屏口径）：面板 / 九键 / 笔画 / 手写共用档位。 */
internal fun keyboardSidePaddingDp(isTablet: Boolean): Float =
    if (isTablet) TABLET_KEYBOARD_SIDE_PADDING_DP.toFloat() else PHONE_KEYBOARD_SIDE_PADDING_DP.toFloat()

/**
 * 键盘区外围留白档：**面板（数字 / 常用符号 / 全量符号）与九键、笔画、手写键盘共用**
 * （后三者原来各写各的 4dp/50dp，平板上与 26 键/面板不一致，现全部收敛到这一条）。
 *
 * - 手机竖屏：两侧 4dp + 底部 8dp；
 * - 手机横屏：上下 2dp + 左右 50dp（拇指缩进，键矮场景）；
 * - 平板：两侧 16dp + 底部 8dp，与 26 键同档（键面距屏幕边 20dp；配合 `keyboard.key.<名>_tablet`
 *   的 4/6dp 键面内衬 ⇒ 相邻键面横 8dp / 竖 12dp）。
 */
internal fun panelOuterPadding(isLandscape: Boolean, isTablet: Boolean): PaddingValues = when {
    isTablet -> PaddingValues(
        start = TABLET_KEYBOARD_SIDE_PADDING_DP.dp,
        end = TABLET_KEYBOARD_SIDE_PADDING_DP.dp,
        bottom = 8.dp,
    )
    isLandscape -> PaddingValues(horizontal = 50.dp, vertical = 2.dp)
    else -> PaddingValues(
        start = PHONE_KEYBOARD_SIDE_PADDING_DP.dp,
        end = PHONE_KEYBOARD_SIDE_PADDING_DP.dp,
        bottom = 8.dp,
    )
}

/** 进入位置记忆：backKeyOnLeft=false 时交换左列末键与中列末行首键（返回键位置对齐进入的键盘）。 */
private fun mirrorColumnsForEntryPosition(layout: PanelLayoutConfig, backKeyOnLeft: Boolean): PanelLayoutConfig {
    if (backKeyOnLeft || !layout.columns) return layout
    val left = layout.left.toMutableList()
    val rows = layout.rows.toMutableList()
    if (left.isEmpty() || rows.isEmpty() || rows.last().isEmpty()) return layout
    val bottomItem = left.removeAt(left.lastIndex)
    if (bottomItem.size != 1) {
        left.add(bottomItem)
        return layout
    }
    val lastRow = rows.removeAt(rows.lastIndex).toMutableList()
    val rowFirst = lastRow.removeAt(0)
    left.add(listOf(rowFirst))
    lastRow.add(0, bottomItem.first())
    rows.add(lastRow)
    return layout.copy(left = left, rows = rows)
}

/** 面板行：功能键走 [FunctionKeyCell]，值键为旧面板同款简单 [KeyButton]（无按压气泡/无滑动）。 */
@Composable
private fun PanelRow(ids: List<String>, env: QwertyRowEnv, modifier: Modifier = Modifier) {
    Row(modifier) {
        ids.forEach { id ->
            val weight = panelKeyWeight(id, env.section)
            PanelKeyCell(id, env, Modifier.weight(weight))
        }
    }
}

/** 行内键宽：keys.<id>.width 优先（面板功能键宽度在段内显式配置），缺省 1 等宽。 */
private fun panelKeyWeight(id: String, section: String): Float =
    KeysConfigHelper.getGestureFor(section, id)?.width?.takeIf { it > 0f } ?: 1f

/** 面板单键：功能键 id 分派功能键组件；其余按值键渲染（键面 = tap.value 或键 id，点击发键值）。 */
@Composable
private fun PanelKeyCell(id: String, env: QwertyRowEnv, modifier: Modifier = Modifier) {
    if (id in KeysConfigHelper.FUNCTION_KEY_IDS) {
        FunctionKeyCell(id, env, modifier)
        return
    }
    val binding = KeysConfigHelper.getGestureFor(env.section, id, env.isAsciiMode)
    val tap = binding?.tap
    val commit = tap?.value?.takeIf { it.isNotEmpty() } ?: id
    KeyButton(
        text = commit,
        onClick = {
            // 面板值键（数字/符号）直接上屏半角 ASCII，绕过 Rime 标点转换：
            // 邮箱/密码/代码场景的 ( ) @ # 等始终半角，不因中文态被转成全角。
            // 仅当显式配置了非上屏动作（命令/复制等）时才走手势分发。
            when (tap?.action) {
                null, GestureAction.SEND_RIME ->
                    env.onCommitText?.invoke(commit) ?: env.onKeyPress(commit)
                else ->
                    dispatchKeyActionOrPress(tap, commit, env.onKeyPress, env.onCommitText, env.onGestureAction)
            }
        },
        backgroundColor = env.keyBackgroundColor,
        textColor = env.keyTextColor,
        modifier = modifier,
        fontSize = env.fontSize,
        onPress = { env.onKeyPressDown?.invoke(commit) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

/**
 * 数字键盘确定键：键面显示回车图标（键面配色/阴影沿用原功能键），
 * 点击仍按 keys.<section>.enter 配置发送回车。
 */
@Composable
private fun PanelEnterCell(env: QwertyRowEnv, modifier: Modifier = Modifier) {
    val tap = KeysConfigHelper.getGestureFor(env.section, "enter", env.isAsciiMode)?.tap
    val value = tap?.value?.takeIf { it.isNotEmpty() } ?: "enter"
    IconKeyButton(
        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardReturn),
        onClick = {
            dispatchKeyActionOrPress(tap, value, env.onKeyPress, env.onCommitText, env.onGestureAction)
        },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        a11yDescription = tr(R.string.s_huiche),
        onPress = { env.onKeyPressDown?.invoke(value) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

/**
 * 数字键盘空格键：键面不再显示「空格」文字，改为空格图标（与回车图标同属 Material 图标体系）；
 * 底色改用数字键底色，图标色沿用功能键文字色，点击仍发送空格。
 */
@Composable
internal fun PanelSpaceCell(env: QwertyRowEnv, modifier: Modifier = Modifier) {
    val tap = KeysConfigHelper.getGestureFor(env.section, "space_bar", env.isAsciiMode)?.tap
    val value = tap?.value?.takeIf { it.isNotEmpty() } ?: "space"
    IconKeyButton(
        icon = rememberVectorPainter(Icons.Filled.SpaceBar),
        onClick = {
            dispatchKeyActionOrPress(tap, value, env.onKeyPress, env.onCommitText, env.onGestureAction)
        },
        backgroundColor = env.keyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        a11yDescription = tr(R.string.keyboard_space),
        onPress = { env.onKeyPressDown?.invoke(value) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

/** rows 模式横屏分体：与主键盘分体同构（左右两半 0.42 + 中间 0.16，行内容拆半）。 */
@Composable
private fun RowsPanelSplitContent(rows: List<List<String>>, env: QwertyRowEnv, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = if (isTablet()) 16.dp else 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(start = 4.dp),
        ) {
            rows.forEach { row ->
                val (left, _) = splitRowForLandscape(row)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    PanelRow(left, env, Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.16f))

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(end = 4.dp),
        ) {
            rows.forEach { row ->
                val (_, right) = splitRowForLandscape(row)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    PanelRow(right, env, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * columns 模式（数字键盘）：三列布局，复刻旧 NumberRows 的结构与比例。
 * 左列 = 键柱区（weight 3f，柱内无间隙、首尾键合并圆角）+ 末键（weight 1f）；
 * 中列 = 数字区（weight 3.4f）；右列 = 功能列（weight 0.8f）。列间 2dp 间距。
 */
@Composable
private fun ColumnsPanelContent(
    layout: PanelLayoutConfig,
    env: QwertyRowEnv,
    operatorSymbols: List<String>,
    editingOperators: Boolean,
    onOperatorDelete: (String) -> Unit,
    onOperatorAdd: () -> Unit,
    onOperatorEditToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val bottomKey = layout.left.lastOrNull()?.singleOrNull()
            OperatorPillar(
                symbols = operatorSymbols,
                editing = editingOperators,
                onTap = { env.onKeyPress(it) },
                onPress = { env.onKeyPressDown?.invoke(it) },
                onDelete = onOperatorDelete,
                onAdd = onOperatorAdd,
                onToggleEdit = onOperatorEditToggle,
                env = env,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                    .padding(LocalKeyVisualPadding.current),
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (bottomKey != null) PanelKeyCell(bottomKey, env, Modifier.weight(1f))
            }
        }

        Column(modifier = Modifier.weight(3.4f).fillMaxHeight()) {
            layout.rows.forEach { row ->
                PanelRow(row, env, Modifier.fillMaxWidth().weight(1f))
            }
        }

        Column(modifier = Modifier.weight(0.8f).fillMaxHeight()) {
            layout.right.forEach { item ->
                val id = item.firstOrNull()
                if (id != null) {
                    // enter 显示回车图标并拉高占两格（原表情键 + 确定键的高度），仍与原四行高度对齐
                    val weight = if (id == "enter") 2f else 1f
                    if (id == "enter") PanelEnterCell(env, Modifier.weight(weight))
                    else PanelKeyCell(id, env, Modifier.weight(weight))
                }
            }
        }
    }
}

/**
 * 面板文本功能键（123/符号/空格）：底色按 [special] 取特殊键色或普通键色，
 * 字号取 [QwertyRowEnv.ctrlFontSize]，点击发 [keyString]（与旧面板 onKeyPress 同路）。
 * 键面可用 keys.<id>.tap.label 覆盖。
 */
@Composable
internal fun PanelTextCell(
    id: String,
    defaultLabel: String,
    keyString: String,
    special: Boolean,
    env: QwertyRowEnv,
    modifier: Modifier = Modifier,
) {
    val label = KeysConfigHelper.getGestureFor(env.section, id, env.isAsciiMode)?.tap?.label
        ?.takeIf { it.isNotEmpty() } ?: defaultLabel
    KeyButton(
        text = label,
        onClick = { env.onKeyPress(keyString) },
        backgroundColor = if (special) env.specialKeyBackgroundColor else env.keyBackgroundColor,
        textColor = if (special) env.specialKeyTextColor else env.keyTextColor,
        modifier = modifier,
        onPress = { env.onKeyPressDown?.invoke(keyString) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
        fontSize = env.ctrlFontSize,
    )
}

/**
 * 面板返回键：配置了 keys.<id>.tap.label 时显示文本（常用符号「返回」），
 * 否则显示返回图标（数字键盘）。点击发 "abc"（返回主键盘，由上层拦截链处理）。
 * [id] 允许右端同名键（back_right，平板设计稿两端各一个）各自配宽度。
 */
@Composable
internal fun PanelBackCell(env: QwertyRowEnv, modifier: Modifier = Modifier, id: String = "back") {
    val label = KeysConfigHelper.getGestureFor(env.section, id, env.isAsciiMode)
        ?.tap?.label?.takeIf { it.isNotEmpty() }
    if (label == null) {
        IconKeyButton(
            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
            onClick = { env.onKeyPress("abc") },
            backgroundColor = env.specialKeyBackgroundColor,
            iconColor = env.specialKeyTextColor,
            modifier = modifier,
            a11yDescription = tr(R.string.s_fanhuizimujianpan),
            onPress = { env.onKeyPressDown?.invoke("abc") },
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
        )
    } else {
        KeyButton(
            text = label,
            onClick = { env.onKeyPress("abc") },
            backgroundColor = env.specialKeyBackgroundColor,
            textColor = env.specialKeyTextColor,
            modifier = modifier,
            onPress = { env.onKeyPressDown?.invoke("abc") },
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
            fontSize = env.ctrlFontSize,
        )
    }
}

/**
 * 数字键盘左列运算符栏：可视区固定为原来的四格高度（布局不变），
 * 上下滑动可查看 `=` 及自由编辑新增的符号；列表末尾常驻“自由编辑”入口。
 */
@Composable
private fun OperatorPillar(
    symbols: List<String>,
    editing: Boolean,
    onTap: (String) -> Unit,
    onPress: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onToggleEdit: () -> Unit,
    env: QwertyRowEnv,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = modifier) {
        // 可视区高度 = 原四键键柱高度，每键等高，保证默认只显示 + - * /
        val keyHeight = maxHeight / 4f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            symbols.forEachIndexed { index, symbol ->
                Box(modifier = Modifier.fillMaxWidth().height(keyHeight)) {
                    OperatorPillarKey(
                        text = symbol,
                        onClick = { if (!editing) onTap(symbol) },
                        backgroundColor = env.keyBackgroundColor,
                        textColor = env.keyTextColor,
                        modifier = Modifier.fillMaxSize(),
                        onPress = if (editing) null else { { onPress(symbol) } },
                        isFirst = index == 0,
                        isLast = false,
                        fontSize = 18.sp,
                        shadowEnabled = env.shadowEnabled,
                        shadowElevation = env.shadowElevation,
                        shadowShapeRadius = env.shadowShapeRadius,
                    )
                    if (editing) {
                        OperatorDeleteBadge(
                            env = env,
                            onDelete = { onDelete(symbol) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp),
                        )
                    }
                }
            }
            if (editing) {
                Box(modifier = Modifier.fillMaxWidth().height(keyHeight)) {
                    OperatorPillarKey(
                        text = "",
                        onClick = onAdd,
                        backgroundColor = env.keyBackgroundColor,
                        textColor = env.keyTextColor,
                        modifier = Modifier.fillMaxSize(),
                        isFirst = false,
                        isLast = false,
                        fontSize = 18.sp,
                        shadowEnabled = env.shadowEnabled,
                        shadowElevation = env.shadowElevation,
                        shadowShapeRadius = env.shadowShapeRadius,
                        icon = Icons.Filled.Add,
                        iconContentDescription = "添加符号",
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(keyHeight)) {
                OperatorPillarKey(
                    text = "",
                    onClick = onToggleEdit,
                    backgroundColor = env.keyBackgroundColor,
                    textColor = env.keyTextColor,
                    modifier = Modifier.fillMaxSize(),
                    isFirst = symbols.isEmpty() && !editing,
                    isLast = true,
                    fontSize = 18.sp,
                    shadowEnabled = env.shadowEnabled,
                    shadowElevation = env.shadowElevation,
                    shadowShapeRadius = env.shadowShapeRadius,
                    icon = if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                    iconContentDescription = if (editing) "完成编辑" else "自由编辑",
                )
            }
        }
    }
}

/** 编辑态删除角标：复用原键盘功能键配色，点角标删除对应符号。 */
@Composable
private fun OperatorDeleteBadge(
    env: QwertyRowEnv,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(env.specialKeyBackgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDelete() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "删除符号",
            tint = env.specialKeyTextColor,
            modifier = Modifier.size(10.dp),
        )
    }
}

/**
 * 运算符键柱单键（数字键盘左列）：首键上圆角、末键下圆角、其余方角，
 * 柱内键无间隙，视觉上连成一柱（复刻旧 NumberSymbolKey）。
 */
@Composable
private fun OperatorPillarKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    fontSize: TextUnit = 18.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp,
    )
    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx),
                )
            }
        } else Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(shadowModifier)
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        currentOnPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { currentOnClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = textColor,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(vertical = 2.dp),
                fontFamily = AppFonts.keyFontFamily,
            )
        }
    }
}
