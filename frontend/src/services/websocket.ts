import { Client } from '@stomp/stompjs';

export type WebSocketCallback = (event: any) => void;

let stompClient: Client | null = null;
const listeners: WebSocketCallback[] = [];

export const initWebSocket = (onMessage: WebSocketCallback) => {
  listeners.push(onMessage);

  if (stompClient && stompClient.connected) {
    return () => {
      const idx = listeners.indexOf(onMessage);
      if (idx !== -1) listeners.splice(idx, 1);
    };
  }

  if (!stompClient) {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerUrl = `${wsProtocol}//${window.location.host}/ws-raw`;

    stompClient = new Client({
      brokerURL: brokerUrl,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      debug: () => {
        // Quiet debug logs
      },
      onConnect: () => {
        stompClient?.subscribe('/topic/sync-events', (message) => {
          try {
            const data = JSON.parse(message.body);
            listeners.forEach((callback) => callback(data));
          } catch (e) {
            console.error('Error parsing WebSocket message:', e);
          }
        });
      },
      onStompError: (frame) => {
        console.debug('STOMP protocol frame:', frame.headers['message']);
      },
      onWebSocketError: (event) => {
        console.debug('WebSocket notification:', event);
      },
    });

    try {
      stompClient.activate();
    } catch (e) {
      console.debug('WebSocket activation pending backend startup:', e);
    }
  }

  return () => {
    const idx = listeners.indexOf(onMessage);
    if (idx !== -1) listeners.splice(idx, 1);
  };
};
