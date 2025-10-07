NowPlayer: Seamless Remote Media Controller
NowPlayer is a two-part application system designed to transform your Android device into a dedicated, aesthetic remote control and desktop media prop for media playing on a Windows PC. The system runs silently in the background, projecting clear, beautiful media status and providing instant playback control without cluttering your main monitor.

The Android app (Ktor Server) hosts the WebSocket connection, displays media details, and sends control commands. The Windows app (C# Client) runs silently in the background, monitors the active media session (Spotify, browser, etc.), and executes commands received from the Android device.

🚀 Key Features
Aesthetic Focus & Desk Prop: Designed with a professional, landscape-optimized dark theme and high-fidelity album art display, complete with a rotating vinyl animation fallback, to serve as a modern, functional piece of workspace decor.

Reliable Bi-directional Communication: Uses a dedicated WebSocket connection (Kotlin Ktor ↔ C# .NET) for stable control.

Smooth Progress Tracking: The Android UI predicts track time using client-side prediction and a 50 ms ticker, ensuring seamless progress bar animation.

Media Control: Instant control over Play/Pause, Next Track, and Previous Track.

Silent PC Client: The C# Windows client runs silently in the background (hidden console), prompting for setup only via an input box.

📦 Plug and Play Deployment
For the easiest setup, download the packaged files from the Deployment_Package folder.

Android Setup (Server)
Transfer the NowPlayer.apk file to your Android device.

Install the APK and open the app.

The app will display the IP Address and Port (e.g., 192.168.1.100:8080/ws) needed for the PC client. Keep this screen open.

Windows Setup (Client)
Download and unzip the NowPlayerPC_v1.0_Standalone.zip package.

Run the NowPlayerPC.exe file inside the folder.

A pop-up box will appear asking for the Android Phone IP Address. Enter only the IP part (e.g., 192.168.1.100).

Once connected, the console window will hide, and the application will run silently in the background.

🛠️ Build and Development Details
PC Client (Program.cs)
The C# client uses a specific architecture to achieve stability and background execution:

Media API: Utilizes the Global System Media Transport Controls (GSMTC) via Windows Runtime (WinRT) APIs to monitor the active media session in Windows.

Threading: Uses the [STAThread] context with a synchronous wrapper to ensure reliable access to COM-based Windows APIs, which is crucial for stability.

Runtime: Deploys as a self-contained executable targeting .NET 8.0-windows for universal compatibility across Windows systems.

Stability: WebSocket sessions are dynamically fetched (sessionManager.GetCurrentSession()) on every command to reliably track media sessions like YouTube.

Android Server (ServerLogic.kt & ServerUI.kt)
Server Framework: Ktor embedded server running on port 8080.

Disconnection Speed: timeoutMillis is aggressively set to 15000 ms to quickly detect silent client disconnects from the PC.

Smooth Time: DashboardScreen implements a LaunchedEffect coroutine that starts a 50 ms ticker to predict the current position based on the last received timestamp, resulting in smooth progress bar animation.

⚠️ Troubleshooting
Connection Failure:

Ensure the Android device and PC are on the same Wi-Fi network.

Verify the IP Address is entered correctly in the PC prompt.

Check your PC's Firewall settings (it may be blocking port 8080).

Controls Not Working (YouTube/Browser):

Ensure the media (video/audio) is currently active in the browser tab.

Try restarting the browser entirely, as browsers sometimes disconnect their media session from Windows controls.

App Closes Immediately (PC):

The application failed a critical startup check. Ensure you run the executable from a location where it has full permissions.
