import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.zai.shell',
  appName: 'Z.ai Shell',
  webDir: 'dist',
  // Cargar la web de z.ai directamente en el WebView nativo
  server: {
    url: 'https://chat.z.ai',
    androidScheme: 'https',
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    // webContentsDebuggingEnabled solo en debug; en release se desactiva via BuildConfig
    webContentsDebuggingEnabled: false,
    backgroundColor: '#0a0a0a',
    initialScale: 1,
    scrollEnabled: true,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 1500,
      backgroundColor: '#0a0a0a',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
      androidSpinnerStyle: 'large',
      iosSpinnerStyle: 'small',
      spinnerColor: '#3b82f6',
      splashFullScreen: true,
      splashImmersive: true,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#0a0a0a',
      overlaysWebView: false,
    },
    LocalNotifications: {
      smallIcon: 'ic_notification',
      iconColor: '#3b82f6',
      sound: 'bell.wav',
    },
    Keyboard: {
      resize: 'body',
      style: 'DARK',
      resizeOnFullScreen: true,
    },
    KeepAwake: {},
  },
};

export default config;
