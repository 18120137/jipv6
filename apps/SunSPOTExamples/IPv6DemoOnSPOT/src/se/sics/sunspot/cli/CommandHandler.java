package se.sics.sunspot.cli;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

public class CommandHandler implements LineListener {

    private Hashtable commands = new Hashtable();

    protected final PrintStream out;
    protected final PrintStream err;
    private Vector currentAsyncCommands = new Vector();
    private int pidCounter = 0;

    public CommandHandler(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;

        registerCommands();
    }

    // Add it to the command table (overwriting anything there)
    public void registerCommand(String cmd, Command command) {
        commands.put(cmd, command);
    }

    public int executeCommand(String commandLine, CommandContext context) {
        String[][] parts;
        PrintStream out = context == null ? this.out : context.out;
        PrintStream err = context == null ? this.err : context.err;

        try {
            parts = CommandParser.parseCommandLine(commandLine);
        } catch (Exception e) {
            err.println("Error: failed to parse command: " + e);
            e.printStackTrace();
            return -1;
        }
        if (parts == null || parts.length == 0) {
            // Nothing to execute
            return 0;
        }
        Command[] cmds = createCommands(parts);
        if(cmds != null && cmds.length > 0) {
            CommandContext[] commands = new CommandContext[parts.length];
            boolean error = false;
            int pid = -1;
            for (int i = 0; i < parts.length; i++) {
                String[] args = parts[i];
                Command cmd = cmds[i];
                if (i == 0 && cmd instanceof AsyncCommand) {
                    pid = ++pidCounter;
                }
                commands[i] = new CommandContext(this, commandLine, args, pid, cmd);
                if (i > 0) {
                    PrintStream po = new PrintStream(new LineOutputStream((LineListener) commands[i].getCommand()));
                    commands[i - 1].setOutput(po, err);
                }
                // Last element also needs output!
                if (i == parts.length - 1) {
                    commands[i].setOutput(out, err);
                }
                // TODO: Check if first command is also LineListener and set it up for input!!
            }
            // Execute when all is set-up in opposite order...
            int index = commands.length - 1;
            try {
                for (; index >= 0; index--) {
                    int code = commands[index].getCommand().executeCommand(commands[index]);
                    if (code != 0) {
                        err.println("command '" + commands[index].getCommandName() + "' failed with error code " + code);
                        error = true;
                        break;
                    }
                }
            } catch (Exception e) {
                err.println("Error: Command failed: " + e.getMessage());
                e.printStackTrace();
                error = true;
            }
            if (error) {
                // Stop any commands that have been started
                for (index++; index < commands.length; index++) {
                    Command command = commands[index].getCommand();
                    if (command instanceof AsyncCommand && !commands[index].hasExited()) {
                        AsyncCommand ac = (AsyncCommand) command;
                        ac.stopCommand(commands[index]);
                    }
                }
                return 1;
            } else if (pid >= 0) {
                synchronized (currentAsyncCommands) {
                    currentAsyncCommands.addElement(commands);
                }
            }
            return 0;
        }
        return -1;
    }

    // This will return an instance that can be configured -
    // which is basically not OK... TODO - fix this!!!
    private Command getCommand(String cmd)  {
        return (Command) commands.get(cmd);
    }

    private Command[] createCommands(String[][] commandList) {
        Command[] cmds = new Command[commandList.length];
        for (int i = 0; i < commandList.length; i++) {
            Command command = getCommand(commandList[i][0]);
            if (command == null) {
                err.println("CLI: Command not found: \"" + commandList[i][0] + "\". Try \"help\".");
                return null;
            }
            if (i > 0 && !(command instanceof LineListener)) {
                err.println("CLI: Error, command \"" + commandList[i][0] + "\" does not take input.");
                return null;
            }
            // TODO replace with command name
            String argHelp = command.getArgumentHelp(null);
            if (argHelp != null) {
                int requiredCount = 0;
                for (int j = 0, m = argHelp.length(); j < m; j++) {
                    if (argHelp.charAt(j) == '<') {
                        requiredCount++;
                    }
                }
                if (requiredCount > commandList[i].length - 1) {
                    // Too few arguments
                    err.println("Too few arguments for " + commandList[i][0]);
                    err.println("Usage: " + commandList[i][0] + ' ' + argHelp);
                    return null;
                }
            }
            cmds[i] = command;
        }
        return cmds;
    }

    private void registerCommands() {
        registerCommand("help", new BasicCommand("show help for the specified command or command list", "[command]") {
            public int executeCommand(CommandContext context) {
                if (context.getArgumentCount() == 0) {
                    context.out.println("Available commands:");
                    Enumeration names = commands.keys();
                    while (names.hasMoreElements()) {
                        String name = (String) names.nextElement();
                        Command command = (Command) commands.get(name);
                        String helpText = command.getCommandHelp(name);
                        if (helpText != null) {
                            String argHelp = command.getArgumentHelp(name);
                            String prefix = argHelp != null ? (' ' + name + ' ' + argHelp) : (' ' + name);
                            int n;
                            if ((n = helpText.indexOf('\n')) > 0) {
                                // Show only first line as short help if help text consists of several lines
                                helpText = helpText.substring(0, n);
                            }
                            context.out.print(prefix);

                            int prefixLen = prefix.length();
                            if (prefixLen < 8) {
                                context.out.print("\t\t\t\t");
                            } else if (prefixLen < 16) {
                                context.out.print("\t\t\t");
                            } else if (prefixLen < 24) {
                                context.out.print("\t\t");
                            } else if (prefixLen < 32) {
                                context.out.print('\t');
                            }
                            context.out.print(' ');
                            context.out.println(helpText);
                        }
                    }
                    return 0;
                }

                String cmd = context.getArgument(0);
                Command command = getCommand(cmd);
                if (command != null) {
                    String helpText = command.getCommandHelp(cmd);
                    String argHelp = command.getArgumentHelp(cmd);
                    context.out.print(cmd);
                    if (argHelp != null && argHelp.length() > 0) {
                        context.out.print(' ' + argHelp);
                    }
                    context.out.println();
                    if (helpText != null && helpText.length() > 0) {
                        context.out.println("  " + helpText);
                    }
                    return 0;
                }
                context.err.println("Error: unknown command '" + cmd + '\'');
                return 1;
            }
        });

        registerCommand("ps", new BasicCommand("list current executing commands", "") {
            public int executeCommand(CommandContext context) {
                for (int i = 0; i < currentAsyncCommands.size(); i++) {
                    CommandContext cmd = ((CommandContext[])currentAsyncCommands.elementAt(i))[0];
                    context.out.println("  " + cmd);
                }
                return 0;
            }
        });

        registerCommand("kill", new BasicCommand("kill a currently executing command", "<process>") {
            public int executeCommand(CommandContext context) {
                int pid = context.getArgumentAsInt(0);
                if (removePid(pid)) {
                    return 0;
                }
                context.err.println("could not find the command to kill.");
                return 1;
            }
        });
    }

    public void exit(CommandContext commandContext, int exitCode, int pid) {
        if (pid >= 0) {
            removePid(pid);
        }
    }

    private boolean removePid(int pid) {
        CommandContext[] contexts = null;
        synchronized (currentAsyncCommands) {
            for (int i = 0, n = currentAsyncCommands.size(); i < n; i++) {
                CommandContext[] cntx = (CommandContext[]) currentAsyncCommands.elementAt(i);
                if (pid == cntx[0].getPID()) {
                    contexts = cntx;
                    currentAsyncCommands.removeElement(cntx);
                    break;
                }
            }
        }
        if (contexts != null) {
            for (int i = 0; i < contexts.length; i++) {
                Command command = contexts[i].getCommand();
                // Stop any commands that have not yet been stopped...
                if (command instanceof AsyncCommand && !contexts[i].hasExited()) {
                    AsyncCommand ac = (AsyncCommand) command;
                    ac.stopCommand(contexts[i]);
                }
            }
            return true;
        }
        return false;
    }

    public void lineRead(String line) {
        executeCommand(line, null);
    }

}
