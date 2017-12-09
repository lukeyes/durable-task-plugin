/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.durabletask;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Plugin;
import hudson.Launcher;
import jenkins.model.Jenkins;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    private final String script;
    private boolean capturingOutput;
    
    @DataBoundConstructor public PowershellScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        List<String> args = new ArrayList<String>();
        PowershellController c = new PowershellController(ws);
        
        String cmd;
        if (capturingOutput) {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -OutputFile '%s' -LogFile '%s' -ResultFile '%s' -CaptureOutput;", 
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellWrapperFile(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -LogFile '%s' -ResultFile '%s';",
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellWrapperFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
       
        // By default PowerShell adds a byte order mark (BOM) to the beginning of a file when using Out-File with a unicode encoding such as UTF8.
        // This causes the Jenkins output to contain bogus characters because Java does not handle the BOM characters by default.
        // This code mimics Out-File, but does not write a BOM.  Hopefully PowerShell will provide a non-BOM option for writing files in future releases.
        String helperScript = "function New-StreamWriter {\r\n" +
        "[CmdletBinding()]\r\n" +
        "param (\r\n" +
        "  [Parameter(Mandatory=$true)] [string] $FilePath,\r\n" +
        "  [Parameter(Mandatory=$true)] [System.Text.Encoding] $Encoding\r\n" +
        ")\r\n" +
        "  [string]$FullFilePath = [IO.Path]::GetFullPath( $FilePath );\r\n" +
        "  [System.IO.StreamWriter]$writer = [System.IO.StreamWriter]::new( $FullFilePath, $true, $Encoding );\r\n" +
        "  $writer.AutoFlush = $true;\r\n" +
        "  return $writer;\r\n" +
        "}\r\n" +
        "\r\n" +
        "function Out-FileNoBom {\r\n" +
        "[CmdletBinding()]\r\n" +
        "param(\r\n" +
        "  [Parameter(Mandatory=$true, Position=0)] [System.IO.StreamWriter] $Writer,\r\n" +
        "  [Parameter(ValueFromPipeline = $true)]   [object] $InputObject\r\n" +
        ")\r\n" +
        "  Process {\r\n" +
        "    $Input | Out-String -Stream -Width 192 | ForEach-Object { $writer.WriteLine( $_ ); }\r\n" +
        "  }\r\n" +
        "}\r\n" +
        "\r\n" +
        "function Execute-AndWriteOutput {\r\n" +
        "[CmdletBinding()]\r\n" +
        "param(\r\n" +
        "  [Parameter(Mandatory=$true)]  [string]$MainScript,\r\n" +
        "  [Parameter(Mandatory=$false)] [string]$OutputFile,\r\n" +
        "  [Parameter(Mandatory=$true)]  [string]$LogFile,\r\n" +
        "  [Parameter(Mandatory=$true)]  [string]$ResultFile,\r\n" +
        "  [Parameter(Mandatory=$false)] [switch]$CaptureOutput\r\n" +
        ")\r\n" +
        "  $exceptionCaught = $null\r\n" +
        "  try {\r\n" +
        "    [System.Text.Encoding] $encoding = [System.Text.UTF8Encoding]::new( $false );\r\n" +
        "    [System.Console]::OutputEncoding = [System.Console]::InputEncoding = $encoding;\r\n" +
        "    [System.IO.Directory]::SetCurrentDirectory( $PWD );\r\n" +
        "    $null = New-Item $LogFile -ItemType File -Force;\r\n" +
        "    [System.IO.StreamWriter] $LogWriter = New-StreamWriter -FilePath $LogFile -Encoding $encoding;\r\n" +
        "    & {\r\n" +
        "      if ($CaptureOutput -eq $true) {\r\n" +
        "        $null = New-Item $OutputFile -ItemType File -Force;\r\n" +
        "        [System.IO.StreamWriter]$OutputWriter = New-StreamWriter -FilePath $OutputFile -Encoding $encoding;\r\n" +
        "        & $MainScript | Out-FileNoBom -Writer $OutputWriter;\r\n" +
        "      } else {\r\n" +
        "        & $MainScript;\r\n" +
        "      }\r\n" +
        "    } *>&1 | Out-FileNoBom -Writer $LogWriter;\r\n" +
        "  } catch {\r\n" +
        "    $exceptionCaught = $_;\r\n" +
        "    $exceptionCaught | Out-String -Width 192 | Out-FileNoBom -Writer $LogWriter;\r\n" +
        "    $exceptionCaught = $true;\r\n" +
        "  } finally {\r\n" +
        "    $exitCode = 0;\r\n" +
        "    if ($LastExitCode -ne $null) {\r\n" +
        "      if ($LastExitCode -eq 0 -and !$?) {\r\n" +
        "        $exitCode = 1;\r\n" +
        "      } else {\r\n" +
        "        $exitCode = $LastExitCode;\r\n" +
        "      }\r\n" +
        "    } elseif ($exceptionCaught -ne $null -or !$?) {\r\n" +
        "      $exitCode = 1;\r\n" +
        "    }\r\n" +
        "    $exitCode | Out-File -FilePath $ResultFile -Encoding ASCII;\r\n" +
        "    if ($CaptureOutput -eq $true -and !(Test-Path $OutputFile)) {\r\n" +
        "      $null = New-Item $OutputFile -ItemType File -Force;\r\n" +
        "    }\r\n" +
        "    if (!(Test-Path $LogFile)) {\r\n" +
        "      $null = New-Item $LogFile -ItemType File -Force;\r\n" +
        "    }\r\n" +
        "    if ($CaptureOutput -eq $true -and $OutputWriter -ne $null) {\r\n" +
        "        $OutputWriter.Flush();\r\n" +
        "        $OutputWriter.Dispose();\r\n" +
        "    }\r\n" +
        "    if ($LogWriter -ne $null) {\r\n" +
        "        $LogWriter.Flush();\r\n" +
        "        $LogWriter.Dispose();\r\n" +
        "    }\r\n" +
        "    exit $exitCode;\r\n" +
        "  }\r\n" +
        "}";
        
        String powershellBinary;
        String powershellArgs;
        if (launcher.isUnix()) {
            powershellBinary = "pwsh";
            powershellArgs = "-NoProfile -NonInteractive";
        } else {
            powershellBinary = "powershell.exe";
            powershellArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass";
        }
        args.add(powershellBinary);
        args.addAll(Arrays.asList(powershellArgs.split(" ")));
        args.addAll(Arrays.asList("-Command", cmd));
        
        String scriptWrapper = String.format("[CmdletBinding()]\r\nparam()\r\n%s %s -File '%s';", powershellBinary, powershellArgs, quote(c.getPowerShellScriptFile(ws)));
                   
        if (launcher.isUnix()) {
            // There is no need to add a BOM with Open PowerShell
            c.getPowerShellHelperFile(ws).write(helperScript, "UTF-8");
            c.getPowerShellScriptFile(ws).write(script, "UTF-8");
            c.getPowerShellWrapperFile(ws).write(scriptWrapper, "UTF-8");
        } else {
            // Write the Windows PowerShell scripts out with a UTF8 BOM
            writeWithBom(c.getPowerShellHelperFile(ws), helperScript);
            writeWithBom(c.getPowerShellScriptFile(ws), script);
            writeWithBom(c.getPowerShellWrapperFile(ws), scriptWrapper);
        }
        
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+(\\\\|/)", "") + "] Running PowerShell script");
        ps.readStdout().readStderr();
        ps.start();

        return c;
    }
    
    private static String quote(FilePath f) {
        return f.getRemote().replace("$", "`$");
    }
    
    // In order for PowerShell to properly read a script that contains unicode characters the script should have a BOM, but there is no built in support for
    // writing UTF-8 with BOM in Java.  This code writes a UTF-8 BOM before writing the file contents.
    private static void writeWithBom(FilePath f, String contents) throws IOException, InterruptedException {
        OutputStream out = f.write();
        out.write(new byte[] { (byte)0xEF, (byte)0xBB, (byte)0xBF });
        out.write(contents.getBytes(Charset.forName("UTF-8")));
        out.flush();
        out.close();
    }

    private static final class PowershellController extends FileMonitoringController {
        private PowershellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }
        
        public FilePath getPowerShellScriptFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellScript.ps1");
        }
        
        public FilePath getPowerShellHelperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellHelper.ps1");
        }
        
        public FilePath getPowerShellWrapperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellWrapper.ps1");
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.PowershellScript_powershell();
        }

    }

}
