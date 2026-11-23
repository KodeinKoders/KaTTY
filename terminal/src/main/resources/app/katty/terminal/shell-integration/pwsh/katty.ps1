# KaTTY's shell integration for PowerShell (pwsh 7, and the Windows PowerShell 5.1 of every Windows): it tells the terminal where the prompts
# are (OSC 133), and which directory the shell is in (OSC 7).
#
# A: a prompt starts. B: what the user types starts. C: the command runs, and its output starts. D;status: it ended.
#
# It is given to the shell with -NoExit -EncodedCommand, after the user's profile: the prompt that the profile set (a theme's) is wrapped, not
# replaced, and no file is run, which the execution policy of Windows PowerShell (Restricted by default) would refuse.

if (-not $global:__KattyIntegration) {
    $global:__KattyIntegration = @{
        Prompt   = $function:prompt
        ReadLine = $function:PSConsoleHostReadLine
        Running  = $false
    }

    # What PowerShell reads of the programs it runs, and gives them, in UTF-8: the console's code page is the system's otherwise (437, 850…).
    try {
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        $global:OutputEncoding = [System.Text.Encoding]::UTF8
    } catch {}

    # PSReadLine colors the command line for a dark background: white text, bright yellow commands, bright white numbers, which a light one does
    # not show. When KaTTY says that the background is light (the theme it had when the shell started), each color that is still PSReadLine's
    # default is made one that reads on it: white is the text's own color, a bright color its plain one. A color that the profile set is kept.
    # The defaults are the ones of PSReadLine 2.0 (Windows PowerShell) to 2.4, as Get-PSReadLineOption gives them.
    if ($global:__KattyLightBackground -and (Get-Command Get-PSReadLineOption -ErrorAction SilentlyContinue)) {
        try {
            $esc = [char]27
            $light = [ordered]@{
                ContinuationPrompt     = @(@('37'), '39')
                Default                = @(@('37'), '39')
                Keyword                = @(@('92'), '32')
                Variable               = @(@('92'), '32')
                Command                = @(@('93'), '33')
                Type                   = @(@('37'), '39')
                Number                 = @(@('97'), '39')
                Member                 = @(@('97', '37'), '39')
                Emphasis               = @(@('96'), '36')
                Error                  = @(@('91'), '31')
                Selection              = @(@('30;47'), '7')
                InlinePrediction       = @(@('97;2;3'), '90;3')
                ListPredictionSelected = @(@('48;5;238'), '48;5;252')
                ListPredictionTooltip  = @(@('97;2;3'), '90;3')
            }
            $options = Get-PSReadLineOption
            $colors = @{}
            foreach ($kind in $light.Keys) {
                $property = if ($kind -eq 'Default') { 'DefaultTokenColor' } else { "${kind}Color" }
                # A version of PSReadLine that does not have it (2.0 has no prediction) is left alone.
                if (-not $options.PSObject.Properties[$property]) { continue }
                $defaults, $replacement = $light[$kind]
                if ($defaults | Where-Object { $options.$property -eq "$esc[$($_)m" }) { $colors[$kind] = "$esc[$($replacement)m" }
            }
            if ($colors.Count) { Set-PSReadLineOption -Colors $colors }
        } catch {}
    }

    # The directory as a file:// URL, which is what OSC 7 carries: a drive is `/C:/…`, and what is not ASCII is its UTF-8 bytes in percents.
    function global:__KattyDirectory {
        $location = $ExecutionContext.SessionState.Path.CurrentLocation
        if ($location.Provider.Name -ne 'FileSystem') { return $null }
        $path = $location.ProviderPath
        # A share of the network (\\server\share) is not this machine's.
        if ($path.StartsWith('\\')) { return $null }
        if ([System.IO.Path]::DirectorySeparatorChar -eq '\') { $path = $path.Replace('\', '/') }
        if (-not $path.StartsWith('/')) { $path = '/' + $path }
        $encoded = [System.Text.StringBuilder]::new()
        foreach ($byte in [System.Text.Encoding]::UTF8.GetBytes($path)) {
            $char = [char]$byte
            if ($byte -lt 128 -and ($char -match '[A-Za-z0-9/._~:-]')) { [void]$encoded.Append($char) } else { [void]$encoded.Append('%{0:X2}' -f $byte) }
        }
        "file://$([Environment]::MachineName)$encoded"
    }

    function global:prompt {
        # The first thing: whether the command that ended failed, and its code when it was a program's.
        $succeeded = $global:?
        $exitCode = $global:LASTEXITCODE
        $state = $global:__KattyIntegration
        $esc = [char]27
        $bel = [char]7
        $before = ''
        if ($state.Running) {
            $status = if ($succeeded) { 0 } elseif ($exitCode -is [int] -and $exitCode -ne 0) { $exitCode } else { 1 }
            $before += "$esc]133;D;$status$bel"
            $state.Running = $false
        }
        # The cursor that the user chose, and shown: a program that ended badly may have left its own, or none.
        $before += "$esc]133;A$bel$esc[0 q$esc[?25h"
        $directory = __KattyDirectory
        if ($directory) { $before += "$esc]7;$directory$bel" }
        # Written before the prompt is made, so that it is before what a theme that writes its prompt itself (Write-Host) shows.
        [Console]::Write($before)
        $text = if ($state.Prompt) { & $state.Prompt } else { "PS $($ExecutionContext.SessionState.Path.CurrentLocation)$('>' * ($NestedPromptLevel + 1)) " }
        # What the prompt ran (a theme runs git) does not change what the user's next command sees.
        $global:LASTEXITCODE = $exitCode
        "$text$esc]133;B$bel"
    }

    # PSReadLine reads the command line through this function, and returns once the line is accepted and the cursor is on the next row, where
    # the output starts: whichever key accepted it. Without PSReadLine there is no such function, and commands are not marked.
    if ($global:__KattyIntegration.ReadLine) {
        function global:PSConsoleHostReadLine {
            $line = & $global:__KattyIntegration.ReadLine
            # A line with nothing to run is no command: zsh and bash mark none either.
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                $global:__KattyIntegration.Running = $true
                [Console]::Write("$([char]27)[0 q$([char]27)]133;C$([char]7)")
            }
            $line
        }
    }
}
