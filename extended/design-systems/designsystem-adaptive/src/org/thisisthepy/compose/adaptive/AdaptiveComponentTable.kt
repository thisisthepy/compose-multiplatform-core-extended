package org.thisisthepy.compose.adaptive

/**
 * The seven Layer 1 libraries that Layer 2 (`org.thisisthepy.compose.adaptive`) maps onto:
 * the six component design systems plus the Liquid Glass material layer.
 */
enum class Layer1Library {
    Material3,
    Cupertino,
    Fluent,
    Gnome,
    Breeze,
    Deepin,
    LiquidGlass,
}

/**
 * One cell of the adaptive x Layer 1 correspondence table.
 *
 * When [native] is `false`, the Layer 1 library draws the component in that system's visual
 * style marked with `@NonNativeComponent` and cites [guidelineUrl], while Layer 2 draws
 * [adaptiveSubstitute] by default.
 */
data class ComponentCell(
    val component: String,
    val library: Layer1Library,
    val layer1Target: String,
    val native: Boolean = true,
    val guidelineUrl: String? = null,
    val adaptiveSubstitute: String = layer1Target,
)

/**
 * Authoritative mapping from Layer 2 adaptive components (FR-15 core vocabulary plus the
 * non-standard candidates) to every Layer 1 library.
 */
object AdaptiveComponentTable {
    val coreComponents: List<String> = listOf(
        "Button",
        "Text",
        "TextField",
        "Checkbox",
        "RadioButton",
        "Switch",
        "Slider",
        "ProgressIndicator",
        "Divider",
        "Card",
        "Surface",
        "Dialog",
        "Menu",
        "Tooltip",
        "Tabs",
        "TopAppBar",
        "NavigationBar",
        "NavigationRail",
        "Badge",
        "SplitPane",
        "DatePicker",
        "TimePicker",
        "DropdownPicker",
        "FloatingActionButton",
        "Chip",
        "Snackbar",
        "Carousel",
    )

    private const val HIG_APPLE = "https://developer.apple.com/design/human-interface-guidelines/"
    private const val HIG_FLUENT = "https://fluent2.microsoft.design/"
    private const val HIG_GNOME = "https://developer.gnome.org/hig/"
    private const val HIG_BREEZE = "https://develop.kde.org/hig/"
    private const val HIG_DEEPIN = "https://www.deepin.org/en/developer-community/design/"

    val cells: List<ComponentCell> = buildList {
        for (component in coreComponents) {
            for (library in Layer1Library.entries) {
                add(resolveCell(component, library))
            }
        }
    }

    fun cell(component: String, library: Layer1Library): ComponentCell? =
        cells.firstOrNull { it.component == component && it.library == library }

    fun nonStandardCells(): List<ComponentCell> = cells.filter { !it.native }

    private fun resolveCell(component: String, library: Layer1Library): ComponentCell = when (component) {
        "FloatingActionButton" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.FloatingActionButton")
            Layer1Library.Cupertino -> ComponentCell(
                component, library, "cupertino.CupertinoFloatingActionButton",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "CupertinoNavigationBar trailing '+' button",
            )
            Layer1Library.Fluent -> ComponentCell(
                component, library, "fluent.FluentFloatingActionButton",
                native = false, guidelineUrl = HIG_FLUENT,
                adaptiveSubstitute = "Fluent CommandBar primary accent button",
            )
            Layer1Library.Gnome -> ComponentCell(
                component, library, "gnome.AdwaitaFloatingActionButton",
                native = false, guidelineUrl = HIG_GNOME,
                adaptiveSubstitute = "Adwaita HeaderBar suggested-action button",
            )
            Layer1Library.Breeze -> ComponentCell(
                component, library, "breeze.BreezeFloatingActionButton",
                native = false, guidelineUrl = HIG_BREEZE,
                adaptiveSubstitute = "Breeze ToolBar primary action button",
            )
            Layer1Library.Deepin -> ComponentCell(
                component, library, "deepin.DeepinFloatingActionButton",
                native = false, guidelineUrl = HIG_DEEPIN,
                adaptiveSubstitute = "Deepin TitleBar primary action button",
            )
            Layer1Library.LiquidGlass -> ComponentCell(
                component, library, "liquidglass.GlassFloatingActionButton",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "LiquidGlass toolbar trailing '+' glass capsule",
            )
        }
        "NavigationRail" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.NavigationRail")
            Layer1Library.Cupertino -> ComponentCell(
                component, library, "cupertino.CupertinoNavigationRail",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "Cupertino sidebar / floating tab bar",
            )
            Layer1Library.Fluent -> ComponentCell(component, library, "fluent.FluentNavigationView(compact)")
            Layer1Library.Gnome -> ComponentCell(
                component, library, "gnome.AdwaitaNavigationRail",
                native = false, guidelineUrl = HIG_GNOME,
                adaptiveSubstitute = "Adwaita ViewSwitcherBar / NavigationSplitView sidebar",
            )
            Layer1Library.Breeze -> ComponentCell(component, library, "breeze.BreezeSidebarRail")
            Layer1Library.Deepin -> ComponentCell(component, library, "deepin.DeepinSidebarRail")
            Layer1Library.LiquidGlass -> ComponentCell(
                component, library, "liquidglass.GlassNavigationRail",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "LiquidGlass floating sidebar capsule",
            )
        }
        "Snackbar" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.Snackbar")
            Layer1Library.Cupertino -> ComponentCell(
                component, library, "cupertino.CupertinoSnackbar",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "Cupertino top capsule banner / inline status toast",
            )
            Layer1Library.Fluent -> ComponentCell(
                component, library, "fluent.FluentSnackbar",
                native = false, guidelineUrl = HIG_FLUENT,
                adaptiveSubstitute = "Fluent InfoBar",
            )
            Layer1Library.Gnome -> ComponentCell(component, library, "gnome.AdwaitaToast")
            Layer1Library.Breeze -> ComponentCell(component, library, "breeze.BreezePassiveNotification")
            Layer1Library.Deepin -> ComponentCell(component, library, "deepin.DeepinFloatingMessage")
            Layer1Library.LiquidGlass -> ComponentCell(
                component, library, "liquidglass.GlassToast",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "LiquidGlass floating status pill",
            )
        }
        "Chip" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.FilterChip")
            Layer1Library.Cupertino -> ComponentCell(
                component, library, "cupertino.CupertinoChip",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "Cupertino capsule toggle button / token pill",
            )
            Layer1Library.Fluent -> ComponentCell(component, library, "fluent.FluentTokenTag")
            Layer1Library.Gnome -> ComponentCell(
                component, library, "gnome.AdwaitaChip",
                native = false, guidelineUrl = HIG_GNOME,
                adaptiveSubstitute = "Adwaita pill ToggleButton",
            )
            Layer1Library.Breeze -> ComponentCell(component, library, "breeze.BreezeChip")
            Layer1Library.Deepin -> ComponentCell(component, library, "deepin.DeepinTag")
            Layer1Library.LiquidGlass -> ComponentCell(component, library, "liquidglass.GlassPill")
        }
        "TimePicker" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.TimePicker")
            Layer1Library.Cupertino -> ComponentCell(component, library, "cupertino.CupertinoTimeWheel")
            Layer1Library.Fluent -> ComponentCell(component, library, "fluent.FluentTimeFlyout")
            Layer1Library.Gnome -> ComponentCell(
                component, library, "gnome.AdwaitaTimePicker",
                native = false, guidelineUrl = HIG_GNOME,
                adaptiveSubstitute = "Adwaita SpinRow pair (hours / minutes)",
            )
            Layer1Library.Breeze -> ComponentCell(component, library, "breeze.BreezeTimeSpinBox")
            Layer1Library.Deepin -> ComponentCell(component, library, "deepin.DeepinTimeSpinBox")
            Layer1Library.LiquidGlass -> ComponentCell(component, library, "liquidglass.GlassTimePopover")
        }
        "Tooltip" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.TooltipBox")
            Layer1Library.Cupertino -> ComponentCell(
                component, library, "cupertino.CupertinoTooltip",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "macOS hover tooltip / iOS TipKit popover",
            )
            Layer1Library.Fluent -> ComponentCell(component, library, "fluent.FluentToolTip")
            Layer1Library.Gnome -> ComponentCell(component, library, "gnome.AdwaitaTooltip")
            Layer1Library.Breeze -> ComponentCell(component, library, "breeze.BreezeToolTip")
            Layer1Library.Deepin -> ComponentCell(component, library, "deepin.DeepinToolTip")
            Layer1Library.LiquidGlass -> ComponentCell(
                component, library, "liquidglass.GlassTooltip",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "LiquidGlass hover popover",
            )
        }
        "Carousel" -> when (library) {
            Layer1Library.Material3 -> ComponentCell(component, library, "material3.HorizontalMultiBrowseCarousel")
            Layer1Library.Cupertino -> ComponentCell(component, library, "cupertino.CupertinoPagedScroll")
            Layer1Library.Fluent -> ComponentCell(
                component, library, "fluent.FluentCarousel",
                native = false, guidelineUrl = HIG_FLUENT,
                adaptiveSubstitute = "Fluent FlipView / PipsPager",
            )
            Layer1Library.Gnome -> ComponentCell(component, library, "gnome.AdwaitaCarousel")
            Layer1Library.Breeze -> ComponentCell(
                component, library, "breeze.BreezeCarousel",
                native = false, guidelineUrl = HIG_BREEZE,
                adaptiveSubstitute = "Breeze horizontal paged SwipeView",
            )
            Layer1Library.Deepin -> ComponentCell(
                component, library, "deepin.DeepinCarousel",
                native = false, guidelineUrl = HIG_DEEPIN,
                adaptiveSubstitute = "Deepin horizontal paged view",
            )
            Layer1Library.LiquidGlass -> ComponentCell(
                component, library, "liquidglass.GlassCarousel",
                native = false, guidelineUrl = HIG_APPLE,
                adaptiveSubstitute = "LiquidGlass horizontal paged glass cards",
            )
        }
        else -> ComponentCell(
            component = component,
            library = library,
            layer1Target = "${library.name.lowercase()}.$component",
        )
    }
}
