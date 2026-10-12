// Entry point - se ejecuta cuando Capacitor carga el bundle local
// (cuando server.url esta configurado, esto se ignora pero debe existir)
import './js/zai-shell.js';

// Registrar listeners globales de Capacitor
import { App } from '@capacitor/app';
import { Network } from '@capacitor/network';
import { LocalNotifications } from '@capacitor/local-notifications';
import { Preferences } from '@capacitor/preferences';
import { StatusBar, Style } from '@capacitor/status-bar';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { Haptics, ImpactStyle } from '@capacitor/haptics';

// Configurar status bar
StatusBar.setStyle({ style: Style.Dark });
StatusBar.setBackgroundColor({ color: '#0a0a0a' });

// Activar keep-awake por defecto
KeepAwake.keepAwake().catch(() => {});

// Estado de la app
App.addListener('appStateChange', async ({ isActive }) => {
  console.log('[ZaiShell] App state:', isActive ? 'active' : 'background');
  if (isActive) {
    // Re-asegurar keep-awake al volver a foreground
    KeepAwake.keepAwake().catch(() => {});
  }
});

// Listener de red
Network.addListener('networkStatusChange', (status) => {
  console.log('[ZaiShell] Network:', status.connected ? 'online' : 'offline', status.connectionType);
});

// Inicializar notificaciones locales
LocalNotifications.requestPermissions().catch(() => {});

console.log('[ZaiShell] Native bootstrap complete');
