import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ThumbBreakApp());

class ThumbBreakApp extends StatelessWidget {
  const ThumbBreakApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ThumbBreak',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Colors.black,
        colorScheme: const ColorScheme.dark(primary: Colors.white),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
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
  static const _control = MethodChannel('thumbbreak/control');
  static const _events = EventChannel('thumbbreak/state');

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
        backgroundColor: const Color(0xFF111111),
        title: const Text('Turn on ThumbBreak'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'ThumbBreak needs Accessibility access to count Reels & Shorts '
                'and block them after your daily limit. It only looks at '
                'Instagram and YouTube, and only reads on-screen labels (like a '
                'reel\'s author or title) to tell one reel from the next. '
                'Nothing is saved, and nothing leaves your phone.',
                style: step,
              ),
              const SizedBox(height: 16),
              const Text(
                '1. Open Accessibility → Installed / Downloaded apps → '
                'ThumbBreak → turn it on.',
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
                  style: step.copyWith(fontSize: 12, color: Colors.white54),
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
      backgroundColor: const Color(0xFF111111),
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
    final muted = Colors.white54;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              GestureDetector(
                onLongPress: () => _showViewIds(context),
                child: Text('ThumbBreak',
                    style: TextStyle(color: muted, letterSpacing: 1.5)),
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
                  color: s.limitReached ? Colors.redAccent : Colors.white,
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
                borderRadius: BorderRadius.circular(2),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 3,
                  backgroundColor: Colors.white12,
                  color: s.limitReached ? Colors.redAccent : Colors.white,
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
          border: Border.all(color: Colors.white24),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text(
          'Tap to enable ThumbBreak in Accessibility settings. '
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
        side: const BorderSide(color: Colors.white24),
      ),
    );
  }
}
