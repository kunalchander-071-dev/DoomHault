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

  /// Same thresholds as [Mood] and the overlay badge: calm < 50%, near < 80%, then limit.
  Color forProgress(int total, int limit) => switch (Mood.of(total, limit)) {
        Mood.happy => calm,
        Mood.neutral => near,
        Mood.sleepy => this.limit,
      };

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
  final int extra; // extra reels granted today via "Breathe & watch 3 more"
  final bool serviceEnabled;
  final int sdkInt;

  const BreakState({
    this.instagram = 0,
    this.youtube = 0,
    this.limit = 20,
    this.extra = 0,
    this.serviceEnabled = false,
    this.sdkInt = 0,
  });

  // Android 13+ blocks Accessibility for sideloaded apps until "Allow restricted settings".
  bool get hasRestrictedSettings => sdkInt >= 33;

  int get total => instagram + youtube;
  int get effectiveLimit => limit + extra;
  bool get limitReached => total >= effectiveLimit;

  factory BreakState.fromMap(Map<dynamic, dynamic> m,
      {bool? serviceEnabled, int? sdkInt}) {
    return BreakState(
      instagram: m['instagram'] as int? ?? 0,
      youtube: m['youtube'] as int? ?? 0,
      limit: m['limit'] as int? ?? 20,
      extra: m['extra'] as int? ?? 0,
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

  Future<void> _setLimit(int limit) async {
    await NativeBridge.setLimit(limit.clamp(1, 500));
    _refresh();
  }

  void _openPermissionPrompt() {
    _promptDismissed = false;
    if (!_promptVisible) _showPermissionPrompt(_state.hasRestrictedSettings);
  }

  @override
  Widget build(BuildContext context) {
    final s = _state;
    final c = DoomColors.of(context);
    final message = _moodMessage(s);
    final darkMode = Theme.of(context).brightness == Brightness.dark;

    // No app bar, so set the status/navigation bar icon colors for the cream/dark background.
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: darkMode ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        body: SafeArea(
          // Scrollable so nothing overflows in landscape or with large font sizes.
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
            children: [
              _Header(
                active: s.serviceEnabled,
                onLongPress: () => _showViewIds(context),
              ),
              if (!s.serviceEnabled) ...[
                const SizedBox(height: 16),
                _EnableBanner(onTap: _openPermissionPrompt),
              ],
              const SizedBox(height: 32),
              Center(child: _MoodRing(total: s.total, limit: s.effectiveLimit)),
              const SizedBox(height: 20),
              Center(
                child: Text.rich(
                  TextSpan(children: [
                    TextSpan(
                      text: '${s.total}',
                      style: TextStyle(
                          fontSize: 44, fontWeight: FontWeight.w700, color: c.text),
                    ),
                    TextSpan(
                      text: ' / ${s.effectiveLimit}',
                      style: TextStyle(fontSize: 22, color: c.muted),
                    ),
                  ]),
                ),
              ),
              Center(
                child: Text(
                    s.extra > 0
                        ? 'reels & shorts today · +${s.extra} extra'
                        : 'reels & shorts today',
                    style: TextStyle(color: c.muted, fontSize: 14)),
              ),
              const SizedBox(height: 14),
              Center(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 250),
                  child: Text(
                    message,
                    key: ValueKey(message),
                    textAlign: TextAlign.center,
                    style: TextStyle(color: c.text, fontSize: 15, height: 1.4),
                  ),
                ),
              ),
              const SizedBox(height: 28),
              Row(
                children: [
                  Expanded(child: _AppTile(name: 'Instagram', count: s.instagram)),
                  const SizedBox(width: 12),
                  Expanded(child: _AppTile(name: 'YouTube', count: s.youtube)),
                ],
              ),
              const SizedBox(height: 12),
              _LimitCard(limit: s.limit, onChanged: _setLimit),
              const SizedBox(height: 20),
              Center(
                child: Text(
                  'Resets at midnight · Only Reels & Shorts are counted',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: c.muted, fontSize: 12),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _moodMessage(BreakState s) {
    if (s.limitReached) {
      return "That's all for today.\nReels & Shorts are paused until midnight.";
    }
    return switch (Mood.of(s.total, s.effectiveLimit)) {
      Mood.happy when s.total == 0 => 'A fresh start — no reels yet today.',
      Mood.happy => "You're in control. Enjoy mindfully.",
      Mood.neutral => 'Over halfway. Maybe take a breather?',
      Mood.sleepy => 'Almost done for today. Time to wind down.',
    };
  }
}

/// The sage's mood for a share of the daily limit — same thresholds as the overlay badge
/// (CounterBadge.kt): smile under 50%, neutral to 80%, sleepy from 80%.
enum Mood {
  happy,
  neutral,
  sleepy;

  static Mood of(int total, int limit) {
    final used = limit <= 0 ? 1.0 : total / limit;
    if (used < 0.5) return happy;
    if (used < 0.8) return neutral;
    return sleepy;
  }

  String get asset => 'assets/mood_$name.png';
}

enum _PermissionStep { accessibility, appInfo, notNow, alreadyOn }

class _Header extends StatelessWidget {
  final bool active;
  final VoidCallback onLongPress;
  const _Header({required this.active, required this.onLongPress});

  @override
  Widget build(BuildContext context) {
    final c = DoomColors.of(context);
    final status = active ? c.calm : c.limit;
    return Row(
      children: [
        // Long-press the name: hidden diagnostics (view IDs seen in Instagram/YouTube).
        GestureDetector(
          onLongPress: onLongPress,
          child: Row(
            children: [
              ClipOval(child: Image.asset('assets/avatar.png', width: 32, height: 32)),
              const SizedBox(width: 10),
              Text('DoomHault',
                  style: TextStyle(
                      color: c.text, fontSize: 20, fontWeight: FontWeight.w800)),
            ],
          ),
        ),
        const Spacer(),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: status.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.circle, size: 8, color: status),
              const SizedBox(width: 6),
              Text(active ? 'Active' : 'Off',
                  style: TextStyle(
                      color: status, fontSize: 12, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      ],
    );
  }
}

/// The mood face inside a ring showing how much of today's limit is used.
class _MoodRing extends StatelessWidget {
  final int total;
  final int limit;
  const _MoodRing({required this.total, required this.limit});

  @override
  Widget build(BuildContext context) {
    final c = DoomColors.of(context);
    final mood = Mood.of(total, limit);
    final progress = limit <= 0 ? 1.0 : (total / limit).clamp(0.0, 1.0);
    return SizedBox(
      width: 220,
      height: 220,
      child: Stack(
        fit: StackFit.expand,
        children: [
          TweenAnimationBuilder<double>(
            tween: Tween(end: progress),
            duration: const Duration(milliseconds: 500),
            curve: Curves.easeOutCubic,
            builder: (context, value, _) => CircularProgressIndicator(
              value: value,
              strokeWidth: 12,
              strokeCap: StrokeCap.round,
              backgroundColor: c.track,
              color: c.forProgress(total, limit),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(20),
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 300),
              child: ClipOval(
                key: ValueKey(mood),
                child: Image.asset(mood.asset, fit: BoxFit.cover),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AppTile extends StatelessWidget {
  final String name;
  final int count;
  const _AppTile({required this.name, required this.count});

  @override
  Widget build(BuildContext context) {
    final c = DoomColors.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.border),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(name, style: TextStyle(color: c.muted, fontSize: 13)),
          const SizedBox(height: 4),
          Text('$count',
              style: TextStyle(
                  color: c.text, fontSize: 24, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

/// Daily limit: -/+ stepper and quick presets.
class _LimitCard extends StatelessWidget {
  final int limit;
  final ValueChanged<int> onChanged;
  const _LimitCard({required this.limit, required this.onChanged});

  static const _presets = [10, 15, 20, 30, 50];

  @override
  Widget build(BuildContext context) {
    final c = DoomColors.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 12, 16),
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.border),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('Daily limit',
                  style: TextStyle(
                      color: c.text, fontSize: 15, fontWeight: FontWeight.w600)),
              const Spacer(),
              _StepButton(
                icon: Icons.remove,
                tooltip: 'Lower limit',
                onTap: limit > 1 ? () => onChanged(limit - 1) : null,
              ),
              SizedBox(
                width: 48,
                child: Text('$limit',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        color: c.text, fontSize: 20, fontWeight: FontWeight.w700)),
              ),
              _StepButton(
                icon: Icons.add,
                tooltip: 'Raise limit',
                onTap: limit < 500 ? () => onChanged(limit + 1) : null,
              ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final p in _presets)
                ChoiceChip(
                  label: Text('$p'),
                  selected: limit == p,
                  onSelected: (_) => onChanged(p),
                  showCheckmark: false,
                  selectedColor: c.calm,
                  labelStyle: TextStyle(
                    color: limit == p ? c.background : c.text,
                    fontWeight: FontWeight.w600,
                  ),
                  side: BorderSide(color: c.border),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _EnableBanner extends StatelessWidget {
  final VoidCallback onTap;
  const _EnableBanner({required this.onTap});

  @override
  Widget build(BuildContext context) {
    final c = DoomColors.of(context);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: c.limit.withValues(alpha: 0.08),
        border: Border.all(color: c.limit.withValues(alpha: 0.35)),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('DoomHault is off',
              style: TextStyle(
                  color: c.text, fontSize: 15, fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text('Turn on Accessibility access so it can count and pause Reels & Shorts.',
              style: TextStyle(color: c.muted, fontSize: 13, height: 1.4)),
          const SizedBox(height: 12),
          FilledButton(onPressed: onTap, child: const Text('Turn on')),
        ],
      ),
    );
  }
}

class _StepButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;
  const _StepButton({required this.icon, required this.tooltip, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: onTap,
      tooltip: tooltip,
      icon: Icon(icon, size: 20),
      style: IconButton.styleFrom(
        side: BorderSide(color: DoomColors.of(context).border),
      ),
    );
  }
}
