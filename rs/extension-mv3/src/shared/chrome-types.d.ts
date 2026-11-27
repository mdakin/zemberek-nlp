declare namespace chrome {
  namespace runtime {
    type ContextType = "TAB" | "POPUP" | "BACKGROUND" | "OFFSCREEN_DOCUMENT" | "SIDE_PANEL";
    interface ExtensionContext {
      contextType: ContextType;
      contextId: string;
      documentUrl?: string;
    }
    interface Port {
      name: string;
      postMessage(message: unknown): void;
      disconnect(): void;
      onMessage: {
        addListener(callback: (message: any, port: Port) => void): void;
        removeListener(callback: (message: any, port: Port) => void): void;
      };
      onDisconnect: {
        addListener(callback: (port: Port) => void): void;
      };
    }
    interface MessageSender {
      tab?: tabs.Tab;
      url?: string;
      id?: string;
    }
    function getURL(path: string): string;
    function getContexts(filter: { contextTypes?: ContextType[] }): Promise<ExtensionContext[]>;
    function sendMessage<T = any>(message: unknown): Promise<T>;
    function connect(connectInfo?: { name?: string }): Port;
    function openOptionsPage(): Promise<void>;
    const onInstalled: {
      addListener(callback: (details: { reason: "install" | "update" | "chrome_update" }) => void): void;
    };
    const onStartup: {
      addListener(callback: () => void): void;
    };
    const onMessage: {
      addListener(
        callback: (
          message: any,
          sender: MessageSender,
          sendResponse: (response?: any) => void
        ) => boolean | void
      ): void;
    };
    const onConnect: {
      addListener(callback: (port: Port) => void): void;
    };
  }

  namespace offscreen {
    type Reason = "WORKERS" | "CLIPBOARD" | "DOM_PARSER";
    function createDocument(parameters: {
      url: string;
      reasons: Reason[];
      justification: string;
    }): Promise<void>;
    function closeDocument(): Promise<void>;
  }

  namespace storage {
    interface StorageArea {
      get(keys?: string | string[] | Record<string, any> | null): Promise<Record<string, any>>;
      set(items: Record<string, any>): Promise<void>;
      remove(keys: string | string[]): Promise<void>;
      clear(): Promise<void>;
    }
    interface StorageChange {
      oldValue?: any;
      newValue?: any;
    }
    const local: StorageArea;
    const sync: StorageArea;
    const session: StorageArea;
    const onChanged: {
      addListener(
        callback: (changes: Record<string, StorageChange>, areaName: "sync" | "local" | "session") => void
      ): void;
    };
  }

  namespace tabs {
    interface Tab {
      id?: number;
      windowId: number;
      active?: boolean;
      url?: string;
      title?: string;
    }
    function query(queryInfo: { active?: boolean; currentWindow?: boolean }): Promise<Tab[]>;
    function sendMessage<T = any>(tabId: number, message: unknown): Promise<T>;
  }

  namespace sidePanel {
    function open(options: { windowId?: number; tabId?: number }): Promise<void>;
    function setPanelBehavior(behavior: { openPanelOnActionClick: boolean }): Promise<void>;
  }

  namespace contextMenus {
    interface OnClickData {
      menuItemId: string | number;
      selectionText?: string;
      editable: boolean;
    }
    function create(
      createProperties: {
        id: string;
        title: string;
        contexts: Array<"page" | "selection" | "editable" | "action">;
      },
      callback?: () => void
    ): void;
    function removeAll(): Promise<void>;
    const onClicked: {
      addListener(callback: (info: OnClickData, tab?: tabs.Tab) => void): void;
    };
  }

  namespace i18n {
    interface LanguageDetectionResult {
      isReliable: boolean;
      languages: Array<{ language: string; percentage: number }>;
    }
    function detectLanguage(text: string): Promise<LanguageDetectionResult>;
  }
}
