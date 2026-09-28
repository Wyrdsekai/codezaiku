package org.codezaiku;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.aws.AwsCredentials;
import org.codezaiku.drive.aws.Bedrock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.util.Locale;
/**
 * {@code codezaiku bedrock …}: using the models of one's own AWS account. It shows what the account offers, tries one model
 * with one small request so that a missing permission shows now and not in the middle of a task, and writes the choice into
 * the settings. Every message is written for someone who uses AWS through a sign-in somebody else set up.
 */
final class BedrockCli {

    private BedrockCli() { }

    static final String USAGE = """
            codezaiku bedrock models [--region <r>] [--profile <p>]       the models your AWS account offers in a region
            codezaiku bedrock test <model> [--region <r>] [--profile <p>]  one small request to that model, to see that you are allowed to use it
            codezaiku bedrock use <model> [--region <r>] [--profile <p>]   test it, then make it the model CodeZaiku uses
            Before any of these, sign in to AWS the way you usually do: `aws sso login`, or `aws configure` once for keys.""";

    /** {@code args[0]} is "bedrock". */
    static int run(String[] args) {
        String verb = args.length > 1 ? args[1] : "";
        String region = "", profile = "";
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--region") && i + 1 < args.length) region = args[++i];
            else if (args[i].equals("--profile") && i + 1 < args.length) profile = args[++i];
            else rest.add(args[i]);
        }
        final String givenRegion = region, givenProfile = profile;
        try {
            Bedrock.Settings s = Bedrock.settings(givenRegion.isBlank() ? "bedrock" : "bedrock:" + givenRegion, k -> k.endsWith("PROFILE") && !givenProfile.isBlank() ? givenProfile : Config.get(k), System.getenv());
            Bedrock bedrock = new Bedrock(s);
            switch (verb) {
                case "models" -> { return models(bedrock); }
                case "test" -> { if (rest.isEmpty()) break; return test(bedrock, rest.get(0)) ? 0 : 1; }
                case "use" -> {
                    if (rest.isEmpty()) break;
                    if (!test(bedrock, rest.get(0))) { System.out.println("\nNothing was changed in your settings."); return 1; }
                    Config.set("drive", "bedrock"); Config.set("bedrock.region", s.region()); Config.set("model", rest.get(0));
                    if (!s.profile().isBlank()) Config.set("bedrock.profile", s.profile());
                    System.out.println("\nCodeZaiku now uses " + rest.get(0) + " on Amazon Bedrock, in the region " + s.region() + (s.profile().isBlank() ? "" : ", with your AWS profile " + s.profile()) + "."
                            + "\nNothing of your AWS sign-in was saved here: each time, CodeZaiku asks the AWS command line for it. What you are charged for the model is on your AWS bill."
                            + "\nA reply from Bedrock is shown when it is complete, not word by word as it is written."
                            + "\n`codezaiku doctor` checks the whole setup.");
                    return 0;
                }
                default -> { }
            }
        } catch (Bedrock.Refused | AwsCredentials.Unavailable e) { System.out.println(e.getMessage()); return 1; }
        catch (IOException e) { System.out.println("The settings could not be written: " + e.getMessage()); return 1; }
        System.err.println(USAGE);
        return 2;
    }

    private static int models(Bedrock bedrock) {
        List<Bedrock.Model> all = bedrock.models();
        System.out.println("These are the models Amazon Bedrock offers your AWS account in the region " + bedrock.settings().region() + ". Whether you may USE one is decided in your AWS account; `codezaiku bedrock test <model>` finds out.\n");
        String[][] groups = {{"Models that write and reason (use one as CodeZaiku's model)", "chat"}, {"Inference profiles (some models are called only through one of these: use the profile's id as the model)", "profile"}};
        for (String[] g : groups) {
            List<Bedrock.Model> of = all.stream().filter(m -> g[1].equals("profile") ? m.profile() && !m.id().toLowerCase(Locale.ROOT).contains("embed") : !m.profile() && !m.embeds()).toList();
            if (of.isEmpty()) continue;
            System.out.println(g[0] + ":");
            for (Bedrock.Model m : of) System.out.println("  " + m.id() + "   " + m.name() + (m.profile() ? "" : " (" + m.provider() + ")") + (m.readsPictures() ? ", reads pictures" : "") + (m.throughProfileOnly() ? ", only through an inference profile" : ""));
            System.out.println();
        }
        if (all.isEmpty()) System.out.println("Bedrock listed no models for this region. Try another region with --region, for example us-east-1 or us-west-2.");
        else System.out.println("To try one and then use it:\n\n    codezaiku bedrock use <the id from the list>\n");
        return 0;
    }

    /** One small request with a tool in it: what every task will need, found out now. */
    private static boolean test(Bedrock bedrock, String model) {
        ObjectMapper j = new ObjectMapper();
        System.out.println("Asking " + model + " on Bedrock (" + bedrock.settings().region() + ") for one short answer …");
        try {
            ObjectNode body = j.createObjectNode();
            body.put("max_tokens", 200);
            body.putArray("messages").addObject().put("role", "user").put("content", "Use the tool to say that you are ready.");
            ObjectNode f = body.putArray("tools").addObject().put("type", "function").putObject("function");
            f.put("name", "ready"); f.put("description", "Say that the model is ready."); f.putObject("parameters").put("type", "object").putObject("properties").putObject("note").put("type", "string");
            ObjectNode reply = bedrock.chat(model, body, Duration.ofSeconds(120));
            boolean usedTool = reply.path("choices").path(0).path("message").path("tool_calls").size() > 0;
            System.out.println("  It answered" + (usedTool ? ", and it can use tools, which CodeZaiku needs for every task." : ". It did NOT use the tool it was offered. CodeZaiku works through tools (reading files, running commands), so it will work poorly with this model. Claude, Llama 3.1 and later, Nova, Qwen and Mistral Large use tools."));
            System.out.println("  CodeZaiku will treat its memory for one conversation as " + Bedrock.contextWindow(model, Config.getInt("CODEZAIKU_BEDROCK_CONTEXT", 0)) + " tokens. If you know it to be different, put `bedrock.context = <number>` in the settings.");
            return true;
        } catch (Bedrock.Refused | AwsCredentials.Unavailable e) { System.out.println("  It did not work. " + e.getMessage()); return false; }
    }
}
