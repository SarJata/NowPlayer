using System;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Windows.Storage.Streams; 
using System.Runtime.InteropServices.WindowsRuntime; 
using Windows.Media.Control; 
using System.Windows.Forms; // CRITICAL: For Application.Run, NotifyIcon, InputBox
using System.Runtime.InteropServices; // Required for P/Invoke ShowWindow
using System.Drawing; // Required for SystemIcons (for NotifyIcon)

namespace NowPlayerPC
{
    class Program
    {
        private static ClientWebSocket? webSocket;
        private static CancellationTokenSource? cts;
        private static string phoneIpAddress = "";
        private static readonly int port = 8080;
        private static Task? keepAliveTask;
        private static Task? receiveTask; 

        private static GlobalSystemMediaTransportControlsSessionManager? SessionManagerInstance;
        private static bool IsClientInitialized = false;

        // --- NEW: System Tray Components ---
        private static NotifyIcon notifyIcon = new NotifyIcon();
        // -----------------------------------

        [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [STAThread]
        static void Main(string[] args)
        {
            // 1. Setup Console (Hidden)
            var handle = System.Diagnostics.Process.GetCurrentProcess().MainWindowHandle;
            if (handle != IntPtr.Zero) ShowWindow(handle, 0); // Hide console immediately

            // 2. Setup System Tray Icon and Menu
            InitializeSystemTray();

            // 3. --- INPUT FIX: Use InputBox ---
            string phoneIp = Microsoft.VisualBasic.Interaction.InputBox("Enter Android Phone IP Address:", "NowPlayer Setup", "192.168.1.x");
            
            if (string.IsNullOrEmpty(phoneIp))
            {
                // Cleanly stop the tray icon if setup is cancelled
                notifyIcon.Visible = false;
                return;
            }

            // 4. Start the primary asynchronous logic on the STA thread.
            Task.Run(() => MainAsync(phoneIp, args)).Wait(); 

            // 5. CRITICAL: Start the WinForms message loop to keep the process alive.
            Application.Run(); 
        }

        static async Task MainAsync(string phoneIp, string[] args)
        {
            phoneIpAddress = phoneIp; 
            Console.WriteLine("=== NowPlayer PC Client Initialized ==="); 
            UpdateTrayStatus("Initializing...", "");

            // 1. Initialize Windows Media Controls
            SessionManagerInstance = await GetSystemMediaTransportControlsSessionManager();
            if (SessionManagerInstance == null)
            {
                 MessageBox.Show("Failed to initialize media session manager. Application shutting down.", "NowPlayer Error");
                 Application.Exit(); 
                 return;
            }
            
            // 2. Start WebSocket connection attempts
            if (!await ConnectToServerWithRetry()) 
            {
                Console.WriteLine("Could not establish connection. Exiting.");
                Application.Exit(); 
                return;
            }
            
            // 3. Mark client as fully initialized and start monitoring
            IsClientInitialized = true;
            UpdateTrayStatus("Connected", "Monitoring media...");

            _ = Task.Run(MonitorMediaAsync);
            _ = Task.Run(ReceiveCommandsAsync); 

            // The process remains alive due to Application.Run() started in Main()
        }

        #region System Tray UI Logic
        
        static void InitializeSystemTray()
        {
            var contextMenu = new ContextMenuStrip();
            contextMenu.Items.Add("Current Status: Disconnected", null, null).Name = "StatusItem";
            contextMenu.Items.Add(new ToolStripSeparator());
            contextMenu.Items.Add("Quit", null, OnQuitClicked);
            
            notifyIcon.Icon = SystemIcons.Application; 
            notifyIcon.ContextMenuStrip = contextMenu;
            notifyIcon.Visible = true;
            notifyIcon.Text = "NowPlayer: Disconnected";
        }

        static void UpdateTrayStatus(string status, string trackInfo)
        {
            // Safely update the tray icon's context menu on the UI thread
            if (notifyIcon.ContextMenuStrip != null)
            {
                // WinForms requires checking InvokeRequired, but since we call Application.Run(), 
                // the main thread handles this. We simplify the call.
                try
                {
                    var statusItem = notifyIcon.ContextMenuStrip.Items["StatusItem"] as ToolStripMenuItem;
                    if (statusItem != null)
                    {
                        statusItem.Text = $"{status} | {trackInfo}";
                    }
                }
                catch { }
            }
            
            // Update the hover text
            notifyIcon.Text = $"NowPlayer: {status}";
        }

        static void OnQuitClicked(object? sender, EventArgs e)
        {
            // Signal cancellation to background tasks
            cts?.Cancel(); 
            // Hide icon and exit the message loop
            notifyIcon.Visible = false;
            Application.Exit(); 
        }

        #endregion

        #region Connection and Media Setup
        
        static async Task<bool> ConnectToServerWithRetry()
        {
            int backoff = 1000;
            
            cts = new CancellationTokenSource(); 
            
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    webSocket = new ClientWebSocket();
                    await ConnectToServer();
                    
                    keepAliveTask = Task.Run(KeepAliveAsync);
                    return true;
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"✗ Connection failed: {ex.Message}");
                    UpdateTrayStatus("Connecting Failed", $"Retrying in {backoff / 1000}s...");
                    await Task.Delay(backoff);
                    backoff = Math.Min(backoff * 2, 16000);
                }
            }
            return false;
        }

        static async Task ConnectToServer()
        {
            webSocket!.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
            var uri = new Uri($"ws://{phoneIpAddress}:{port}/ws");
            await webSocket.ConnectAsync(uri, cts!.Token);
            Console.WriteLine("✓ Connected successfully!\n");
        }
        
        static async Task KeepAliveAsync()
        {
            while (IsClientInitialized && webSocket?.State == WebSocketState.Open && !cts!.IsCancellationRequested)
            {
                try
                {
                    var buffer = new byte[0];
                    await webSocket.SendAsync(new ArraySegment<byte>(buffer), WebSocketMessageType.Binary, true, cts.Token);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error during keepalive: {ex.Message}");
                    await ConnectToServerWithRetry();
                    return;
                }
                await Task.Delay(15000);
            }
        }

        static async Task<Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager?> GetSystemMediaTransportControlsSessionManager()
        {
            try
                {
                    return await Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                }
            catch (Exception ex)
            {
                Console.WriteLine($"Error initializing media controls: {ex.Message}");
                return null;
            }
        }

        #endregion

        #region Command Handling (Receiver)

        static async Task ReceiveCommandsAsync()
        {
            var buffer = new byte[1024 * 4]; 
            
            if (SessionManagerInstance == null || !IsClientInitialized) return;

            while (webSocket?.State == WebSocketState.Open && !cts!.IsCancellationRequested)
            {
                try
                {
                    var result = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), cts!.Token);

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        string command = Encoding.UTF8.GetString(buffer, 0, result.Count);
                        Console.WriteLine($"Command received: {command}");
                        
                        await ExecuteMediaCommand(SessionManagerInstance, command);
                    }
                    else if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Console.WriteLine("Server requested connection close.");
                        break;
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error receiving command: {ex.Message}");
                    break; 
                }
            }
        }

        static async Task ExecuteMediaCommand(GlobalSystemMediaTransportControlsSessionManager sessionManager, string command)
        {
            var currentSession = sessionManager.GetCurrentSession(); 
            
            if (currentSession == null)
            {
                Console.WriteLine("Warning: No active media session to control.");
                return;
            }

            switch (command)
            {
                case "PLAY_PAUSE":
                    var playbackInfo = currentSession.GetPlaybackInfo();
                    if (playbackInfo.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                    {
                        await currentSession.TryPauseAsync(); 
                        Console.WriteLine("-> Paused media.");
                    }
                    else
                    {
                        await currentSession.TryPlayAsync();
                        Console.WriteLine("-> Played media.");
                    }
                    break;
                case "NEXT_TRACK":
                    await currentSession.TrySkipNextAsync();
                    Console.WriteLine("-> Skipped to next track.");
                    break;
                case "PREVIOUS_TRACK":
                    await currentSession.TrySkipPreviousAsync();
                    Console.WriteLine("-> Skipped to previous track.");
                    break;
                default:
                    Console.WriteLine($"Unknown command: {command}");
                    break;
            }
        }

        #endregion

        #region Media Monitoring (Sender)

        static async Task MonitorMediaAsync()
        {
            if (SessionManagerInstance == null || !IsClientInitialized) return;
            var sessionManager = SessionManagerInstance;
            
            NowPlayingData? lastSentData = null;
            while (!cts!.IsCancellationRequested)
            {
                try
                {
                    var currentSession = sessionManager.GetCurrentSession(); 
                    
                    if (currentSession != null)
                    {
                        var mediaProperties = await currentSession.TryGetMediaPropertiesAsync();
                        var playbackInfo = currentSession.GetPlaybackInfo();
                        var timelineProperties = currentSession.GetTimelineProperties();

                        if (mediaProperties != null)
                        {
                            string base64Art = await GetAlbumArtBase64Async(mediaProperties.Thumbnail);

                            var nowPlaying = new NowPlayingData
                            {
                                Title = mediaProperties.Title ?? "Unknown",
                                Artist = mediaProperties.Artist ?? "Unknown",
                                Album = mediaProperties.AlbumTitle ?? "Unknown",
                                AlbumArtUrl = mediaProperties.Thumbnail?.ToString() ?? "",
                                AlbumArtBase64 = base64Art,
                                Duration = (long)timelineProperties.EndTime.TotalMilliseconds,
                                Position = (long)timelineProperties.Position.TotalMilliseconds,
                                IsPlaying = playbackInfo.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
                                Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                            };

                            // Update system tray status before checking equality (UI update is frequent)
                            string status = nowPlaying.IsPlaying ? "▶ Playing" : "❚❚ Paused";
                            UpdateTrayStatus(status, $"{nowPlaying.Artist} - {nowPlaying.Title}");

                            if (!nowPlaying.Equals(lastSentData))
                            {
                                await SendNowPlayingData(nowPlaying);
                                lastSentData = nowPlaying;
                            }
                        }
                    }
                    else
                    {
                        // Clear stale data and update tray status
                        lastSentData = null; 
                        UpdateTrayStatus("Connected - Idle", "No media session active.");
                        await Task.CompletedTask;
                    }
                }
                catch {}

                await Task.Delay(1000);
            }
        }

        static async Task<string> GetAlbumArtBase64Async(IRandomAccessStreamReference thumbnailRef)
        {
            if (thumbnailRef == null) return "";

            try
            {
                using (var stream = await thumbnailRef.OpenReadAsync())
                {
                    var dataReader = new DataReader(stream);
                    uint size = (uint)stream.Size;
                    await dataReader.LoadAsync(size);
                    byte[] bytes = new byte[size];
                    dataReader.ReadBytes(bytes);
                    return Convert.ToBase64String(bytes);
                }
            }
            catch
            {
                return "";
            }
        }

        static async Task SendNowPlayingData(NowPlayingData data)
        {
            if (webSocket?.State != WebSocketState.Open) return;

            try
            {
                var json = JsonConvert.SerializeObject(data);
                var bytes = Encoding.UTF8.GetBytes(json);
                await webSocket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, cts!.Token);
            }
            catch {}
        }
        
        #endregion

        #region Data Model

        public class NowPlayingData : IEquatable<NowPlayingData>
        {
            [JsonProperty("title")]
            public string Title { get; set; } = "";

            [JsonProperty("artist")]
            public string Artist { get; set; } = "";

            [JsonProperty("album")]
            public string Album { get; set; } = "";

            [JsonProperty("albumArtUrl")]
            public string AlbumArtUrl { get; set; } = "";

            [JsonProperty("albumArtBase64")]
            public string AlbumArtBase64 { get; set; } = "";

            [JsonProperty("duration")]
            public long Duration { get; set; }

            [JsonProperty("position")]
            public long Position { get; set; }

            [JsonProperty("isPlaying")]
            public bool IsPlaying { get; set; }

            [JsonProperty("timestamp")]
            public long Timestamp { get; set; }

            public bool Equals(NowPlayingData? other)
            {
                if (other == null) return false;
                return Title == other.Title &&
                       Artist == other.Artist &&
                       Album == other.Album &&
                       IsPlaying == other.IsPlaying &&
                       Math.Abs(Position - other.Position) < 2000;
            }

            public override bool Equals(object? obj) => Equals(obj as NowPlayingData);
            public override int GetHashCode() => HashCode.Combine(Title, Artist, Album, IsPlaying);
        }
        
        #endregion
    }
}
