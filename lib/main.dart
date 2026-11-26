import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const DoomHaultApp());

class DoomHaultApp extends StatelessWidget {
  const DoomHaultApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DoomHault',
      debugShowCheckedModeBanner: false,
      theme: _theme(DoomColors.light),
      darkTheme: _theme(DoomColors.dark),
      home: const HomeScreen(),
    );
  }

  static ThemeData _theme(DoomColors c) {
    final dark = c == DoomColors.dark;
    return ThemeData(
      useMaterial3: true,
      brightness: dark ? Brightness.dark : Brightness.light,
      scaffoldBackgroundColor: c.background,
      colorScheme: (dark ? const ColorScheme.dark() : const ColorScheme.light()).copyWith(
        primary: c.calm,
        onPrimary: dark ? c.background : Colors.white,
        secondary: c.near,
        error: c.limit,
        surface: c.surface,
        onSurface: c.text,
      ),
      dialogTheme: DialogThemeData(backgroundColor: c.surface),
      bottomSheetTheme: BottomSheetThemeData(backgroundColor: c.surface),
      extensions: [c],
    );
  }
}

/// Brand palette ("Sage & Saffron"), taken from the DoomHault logo. Text colors meet
/// WCAG AA (4.5:1) on [background]; the badge in CounterBadge.kt uses the same calm/near/limit.
@immutable
class DoomColors extends ThemeExtension<DoomColors> {
  final Color background; // logo cream / deep green-black
  final Color surface;
  final Color text; // logo wordmark green
  final Color muted;
  final Color calm; // sage: under the limit, buttons
  final Color near; // saffron: close to the limit
  final Color limit; // red: limit reached
  final Color track;
  final Color border;

  const DoomColors({
    required this.background,
    required this.surface,
    required this.text,
    required this.muted,
    required this.calm,
    required this.near,
    required this.limit,
    required this.track,
    required this.border,
  });

  static const light = DoomColors(
    background: Color(0xFFF4EFE4),
    surface: Color(0xFFFBF8F2),
    text: Color(0xFF2E3B2A),
    muted: Color(0xFF6B6457),
    calm: Color(0xFF4E6A46),
    near: Color(0xFFA3561B),
    limit: Color(0xFFB23A30),
    track: Color(0xFFDCE3D3),
    border: Color(0xFFCFC8B8),
  );

  static const dark = DoomColors(
    background: Color(0xFF161B15),
    surface: Color(0xFF20271F),
    text: Color(0xFFECE6D8),
    muted: Color(0xFFA39C8C),
    calm: Color(0xFF9DB58F),
    near: Color(0xFFE39A52),
    limit: Color(0xFFE5786C),
    track: Color(0xFF2E382C),
    border: Color(0xFF3A4537),
  );

  static DoomColors of(BuildContext context) =>
      Theme.of(context).extension<DoomColors>() ?? light;

  /// Calm → near (last 25% or last 2) → limit, same thresholds as the badge.
  Color forProgress(int total, int limit) {
    if (total >= limit) return this.limit;
    if (limit - total <= 2 || total >= limit * 0.75) return near;
    return calm;
  }

  @override
  DoomColors copyWith() => this;

  @override
  DoomColors lerp(ThemeExtension<DoomColors>? other, double t) =>
      t < 0.5 || other is! DoomColors ? this : other;
}

/// Snapshot of what the native accessibility service has recorded today.
class BreakState {
  final int instagram;
  final int youtube;
  final int limit;
  final bool serviceEnabled;
  final int sdkInt;

  const BreakState({
    this.instagram = 0,
    this.youtube = 0,
    this.limit = 20,
    this.serviceEnabled = false,
    this.sdkInt = 0,
  });

  // Android 13+ blocks Accessibility for sideloaded apps until "Allow restricted settings".
  bool get hasRestrictedSettings => sdkInt >= 33;

  int get total => instagram + youtube;
  bool get limitReached => total >= limit;

  factory BreakState.fromMap(Map<dynamic, dynamic> m,
      {bool? serviceEnabled, int? sdkInt}) {
    return BreakState(
      instagram: m['instagram'] as int? ?? 0,
      youtube: m['youtube'] as int? ?? 0,
      limit: m['limit'] as int? ?? 20,
      serviceEnabled: serviceEnabled ?? m['serviceEnabled'] as bool? ?? false,
      sdkInt: sdkInt ?? m['sdkInt'] as int? ?? 0,
    );
  }
}

class NativeBridge {
  static const _control = MethodChannel('doomhault/control');
  static const _events = EventChannel('doomhault/state');

  static Future<BreakState> getState() async {
    final m = await _control.invokeMapMethod<String, dynamic>('getState');
    return BreakState.fromMap(m ?? const {});
  }

  static Future<void> setLimit(int limit) =>
      _control.invokeMethod('setLimit', {'limit': limit});

  static Future<Map<String, String>> getViewIds() async =>
      await _control.invokeMapMethod<String, String>('getViewIds') ?? const {};

  static Future<void> openAccessibilitySettings() =>
      _control.invokeMethod('openAccessibilitySettings');

  static Future<void> openAppInfo() => _control.invokeMethod('openAppInfo');

  static Stream<Map<dynamic, dynamic>> stateStream() =>
      _events.receiveBroadcastStream().cast<Map<dynamic, dynamic>>();
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  BreakState _state = const BreakState();
  StreamSubscription<Map<dynamic, dynamic>>? _sub;
  // Prompt on launch, and again on return from Settings while still off — until "Not now".
  bool _promptVisible = false;
  bool _promptDismissed = false;
  BuildContext? _promptContext;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _sub = NativeBridge.stateStream().listen((m) {
      setState(() => _state = BreakState.fromMap(m,
          serviceEnabled: _state.serviceEnabled, sdkInt: _state.sdkInt));
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  // Coming back from Accessibility settings — re-check if the service is on.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final s = await NativeBridge.getState();
    if (!mounted) return;
    setState(() => _state = s);
    // Turned on while the prompt was up (e.g. via the accessibility shortcut) — close it.
    if (s.serviceEnabled && _promptVisible && _promptContext != null) {
      Navigator.pop(_promptContext!, _PermissionStep.alreadyOn);
      return;
    }
    if (!s.serviceEnabled && !_promptVisible && !_promptDismissed) {
      _showPermissionPrompt(s.hasRestrictedSettings);
    }
  }

  Future<void> _showPermissionPrompt(bool restricted) async {
    _promptVisible = true;
    const step = TextStyle(fontSize: 14, height: 1.4);
    final choice = await showDialog<_PermissionStep>(
      context: context,
      barrierDismissible: false,
      builder: (context) {
        _promptContext = context;
        return AlertDialog(
        title: const Text('Turn on DoomHault'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'DoomHault needs Accessibility access to count Reels & Shorts '
                'and block them after your daily limit. It only looks at '
                'Instagram and YouTube, and only reads on-screen labels (like a '
                'reel\'s author or title) to tell one reel from the next. The '
                'text is never saved — only a short fingerprint of your last few '
                'reels, so one isn\'t counted twice. Nothing leaves your phone.',
                style: step,
              ),
              const SizedBox(height: 16),
              const Text(
                '1. Open Accessibility → Installed / Downloaded apps → '
                'DoomHault → turn it on.',
                style: step,
              ),
              if (restricted) ...[
                const SizedBox(height: 12),
                const Text(
                  '2. If it says "Restricted setting" or the switch is greyed '
                  'out: open App info, tap ⋮ (top right) → Allow restricted '
                  'settings, then do step 1 again.',
                  style: step,
                ),
                const SizedBox(height: 4),
                Text(
                  'The ⋮ option only appears after you\'ve tried step 1 once.',
                  style: step.copyWith(
                      fontSize: 12, color: DoomColors.of(context).muted),
                ),
              ],
            ],
          ),
        ),
        actionsOverflowDirection: VerticalDirection.up,
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, _PermissionStep.notNow),
            child: const Text('Not now'),
          ),
          if (restricted)
            OutlinedButton(
              onPressed: () => Navigator.pop(context, _PermissionStep.appInfo),
              child: const Text('Allow restricted settings'),
            ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(context, _PermissionStep.accessibility),
            child: const Text('Open Accessibility'),
          ),
        ],
      );
      },
    );
    _promptVisible = false;
    _promptContext = null;
    switch (choice) {
      case _PermissionStep.accessibility:
        await NativeBridge.openAccessibilitySettings();
      case _PermissionStep.appInfo:
        await NativeBridge.openAppInfo();
      case _PermissionStep.alreadyOn:
        break;
      case _PermissionStep.notNow:
      case null:
        _promptDismissed = true;
    }
  }

  Future<void> _changeLimit(int delta) async {
    final next = (_state.limit + delta).clamp(1, 500);
    await NativeBridge.setLimit(next);
    _refresh();
  }

  // Hidden diagnostics: view IDs seen on the last Instagram/YouTube screen.
  Future<void> _showViewIds(BuildContext context) async {
    final ids = await NativeBridge.getViewIds();
    final text = ids.entries
        .map((e) => '== ${e.key} ==\n${e.value.isEmpty ? '(nothing yet)' : e.value}')
        .join('\n\n');
    if (!context.mounted) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SizedBox(
          height: MediaQuery.of(context).size.height * 0.75,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 8, 0),
                child: Row(
                  children: [
                    const Text('Last seen view IDs'),
                    const Spacer(),
                    TextButton(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: text));
                        Navigator.pop(context);
                      },
                      child: const Text('Copy'),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: SelectableText(text,
                      style: const TextStyle(
                          fontFamily: 'monospace', fontSize: 12, height: 1.5)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = _state;
    final progress = (s.total / s.limit).clamp(0.0, 1.0);
    final c = DoomColors.of(context);
    final muted = c.muted;
    final stateColor = c.forProgress(s.total, s.limit);
    final darkMode = Theme.of(context).brightness == Brightness.dark;

    // No app bar, so set the status/navigation bar icon colors for the cream/dark background.
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: darkMode ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              GestureDetector(
                onLongPress: () => _showViewIds(context),
                child: Row(
                  children: [
                    ClipOval(
                      child: Image.asset('assets/avatar.png',
                          width: 36, height: 36),
                    ),
                    const SizedBox(width: 12),
                    Text('DoomHault',
                        style: TextStyle(
                            color: c.text,
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 0.5)),
                  ],
                ),
              ),
              if (!s.serviceEnabled) ...[
                const SizedBox(height: 24),
                _EnableBanner(onTap: () {
                  _promptDismissed = false;
                  if (!_promptVisible) {
                    _showPermissionPrompt(s.hasRestrictedSettings);
                  }
                }),
              ],
              const Spacer(),
              Text(
                '${s.total}',
                style: TextStyle(
                  fontSize: 120,
                  fontWeight: FontWeight.w200,
                  height: 1,
                  color: s.limitReached ? c.limit : c.text,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                s.limitReached
                    ? 'Limit reached. Reels & Shorts are blocked.'
                    : 'of ${s.limit} reels & shorts today',
                style: TextStyle(color: muted, fontSize: 16),
              ),
              const SizedBox(height: 24),
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 6,
                  backgroundColor: c.track,
                  color: stateColor,
                ),
              ),
              const SizedBox(height: 16),
              Text('Instagram ${s.instagram}   ·   YouTube ${s.youtube}',
                  style: TextStyle(color: muted, fontSize: 13)),
              const Spacer(),
              Row(
                children: [
                  Text('Daily limit', style: TextStyle(color: muted)),
                  const Spacer(),
                  _StepButton(icon: Icons.remove, onTap: () => _changeLimit(-5)),
                  SizedBox(
                    width: 56,
                    child: Text('${s.limit}',
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 20)),
                  ),
                  _StepButton(icon: Icons.add, onTap: () => _changeLimit(5)),
                ],
              ),
            ],
          ),
        ),
      ),
      ),
    );
  }
}

enum _PermissionStep { accessibility, appInfo, notNow, alreadyOn }

class _EnableBanner extends StatelessWidget {
  final VoidCallback onTap;
  const _EnableBanner({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: DoomColors.of(context).surface,
          border: Border.all(color: DoomColors.of(context).border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text(
          'Tap to enable DoomHault in Accessibility settings. '
          'It\'s needed to count and block Reels & Shorts.',
          style: TextStyle(fontSize: 13, height: 1.4),
        ),
      ),
    );
  }
}

class _StepButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  const _StepButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: onTap,
      icon: Icon(icon, size: 20),
      style: IconButton.styleFrom(
        side: BorderSide(color: DoomColors.of(context).border),
      ),
    );
  }
}
