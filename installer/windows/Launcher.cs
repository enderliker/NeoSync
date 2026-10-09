// Copyright (c) NeoSync contributors
// SPDX-License-Identifier: LGPL-2.1-only
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Launcher
{
    [STAThread]
    private static int Main(string[] args)
    {
        bool check = args.Length == 2 && args[0] == "--wrapper-check";
        try
        {
            string jar = ExtractInstaller();
            string javaHome = FindJava();
            string java = Path.Combine(javaHome, "bin", check || args.Length > 0 ? "java.exe" : "javaw.exe");
            var command = new StringBuilder("-jar " + Quote(jar));
            foreach (string arg in check ? new[] { "--installer-check" } : args) command.Append(" " + Quote(arg));
            var start = new ProcessStartInfo(java, command.ToString()) {
                UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(jar)
            };
            using (var process = Process.Start(start))
            {
                process.WaitForExit();
                if (process.ExitCode != 0) throw new IOException("The NeoSync installer stopped with exit code " + process.ExitCode + ".");
            }
            if (check) File.WriteAllText(Path.GetFullPath(args[1]), "PASS\ninstallerSha256=" + BuildInfo.Sha256 + "\njava=" + java + "\n", Encoding.UTF8);
            return 0;
        }
        catch (Exception error)
        {
            if (check) File.WriteAllText(Path.GetFullPath(args[1]), "FAIL: " + error.Message + "\n", Encoding.UTF8);
            else MessageBox.Show(error.Message, "NeoSync Installer", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string ExtractInstaller()
    {
        string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoSync", "installers", BuildInfo.Sha256);
        for (var dir = new DirectoryInfo(root); dir != null; dir = dir.Parent)
            if (dir.Exists && (dir.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked installer cache folders are not supported.");
        Directory.CreateDirectory(root);
        string destination = Path.Combine(root, "neosync-installer.jar");
        using (var gate = new FileStream(Path.Combine(root, ".extract.lock"), FileMode.OpenOrCreate, FileAccess.Write, FileShare.None))
        {
            if (File.Exists(destination))
            {
                if ((File.GetAttributes(destination) & FileAttributes.ReparsePoint) != 0 || Hash(destination) != BuildInfo.Sha256)
                    throw new IOException("The cached NeoSync installer was modified. Remove this cache folder and reopen the installer: " + root);
                return destination;
            }
            string stage = Path.Combine(root, Guid.NewGuid().ToString("N") + ".tmp");
            try
            {
                using (var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("NeoSync.Installer"))
                using (var file = new FileStream(stage, FileMode.CreateNew, FileAccess.Write, FileShare.None)) resource.CopyTo(file);
                if (Hash(stage) != BuildInfo.Sha256) throw new IOException("The embedded installer failed verification. Download NeoSync again.");
                File.Move(stage, destination);
            }
            finally { if (File.Exists(stage)) File.Delete(stage); }
        }
        return destination;
    }

    private static string Hash(string path)
    {
        using (var file = File.OpenRead(path))
        using (var hash = SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(file)).Replace("-", "").ToLowerInvariant();
    }

    private static string FindJava()
    {
        var candidates = new List<string>();
        string configured = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!String.IsNullOrWhiteSpace(configured)) candidates.Add(configured);
        foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
        using (var registry = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
        foreach (string vendor in new[] { "SOFTWARE\\JavaSoft\\JDK", "SOFTWARE\\JavaSoft\\Java Runtime Environment", "SOFTWARE\\Eclipse Adoptium\\JDK", "SOFTWARE\\Microsoft\\JDK" })
        using (var key = registry.OpenSubKey(vendor))
        {
            if (key == null) continue;
            foreach (string version in key.GetSubKeyNames())
            using (var entry = key.OpenSubKey(version))
            {
                var home = entry.GetValue("JavaHome") as string;
                if (home != null) candidates.Add(home);
            }
        }
        foreach (string directory in new[] {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Eclipse Adoptium"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Java"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Microsoft"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft", "runtime"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PrismLauncher", "java")
        }) CollectJava(directory, 4, candidates);
        foreach (string directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
        {
            if (String.IsNullOrWhiteSpace(directory)) continue;
            try { if (File.Exists(Path.Combine(directory.Trim('"'), "java.exe"))) candidates.Add(Path.GetFullPath(Path.Combine(directory.Trim('"'), ".."))); }
            catch (ArgumentException) { }
        }
        // Prefer the minimum supported runtime before newer installed Java versions.
        foreach (bool exact in new[] { true, false })
        foreach (string home in candidates)
        {
            try
            {
                string release = Path.Combine(home, "release");
                if (!File.Exists(release) || new FileInfo(release).Length > 65536 || !File.Exists(Path.Combine(home, "bin", "java.exe")) || !File.Exists(Path.Combine(home, "bin", "javaw.exe"))) continue;
                var match = Regex.Match(File.ReadAllText(release), "JAVA_VERSION=\"([0-9]+)");
                int major;
                if (match.Success && Int32.TryParse(match.Groups[1].Value, out major) && (exact ? major == 21 : major > 21)) return Path.GetFullPath(home);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
        throw new IOException("NeoSync needs Java 21. Install a 64-bit Java 21 runtime, then reopen this installer.\n\nhttps://adoptium.net/temurin/releases/?version=21");
    }

    private static void CollectJava(string directory, int depth, List<string> candidates)
    {
        if (depth == 0 || !Directory.Exists(directory)) return;
        try
        {
            if ((File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0) return;
            if (File.Exists(Path.Combine(directory, "release"))) { candidates.Add(directory); return; }
            foreach (string child in Directory.GetDirectories(directory)) CollectJava(child, depth - 1, candidates);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in value)
        {
            if (character == '\\') { slashes++; continue; }
            result.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
            result.Append(character);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }
}
