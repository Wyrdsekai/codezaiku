# Using the models in your own AWS account (Amazon Bedrock)

If your organisation has models switched on in Amazon Bedrock, CodeZaiku can use them as its model: Claude, Llama, Amazon
Nova, Qwen, Mistral and the others Bedrock hosts. It uses **your own AWS sign-in**. Nothing of that sign-in is saved by CodeZaiku, and what you may use is decided in your AWS account, the
same way as for everything else you do there.

You need two things on the machine: CodeZaiku, and the AWS command line (`aws`, version 2:
https://aws.amazon.com/cli/). If you already use `aws` for your work, you have it.

## 1. Sign in to AWS, the way you usually do

Most organisations use single sign-on:

```
aws configure sso          # once, to set it up: it asks for your organisation's start address and makes a profile
aws sso login              # each day or so, when the sign-in has run out
```

If you were given access keys instead, `aws configure` stores them, once.

If you have more than one profile, note the name of the one that may use Bedrock. Below it is called `research`.

## 2. See what your account offers

```
codezaiku bedrock models --region us-east-1 --profile research
```

It lists the models in that region in two groups: models that write and reason, and inference profiles.
Some newer models, among them the recent Claude models, are called only through an **inference profile**. For those, use the
profile's id, which begins with a region group such as `us.` or `eu.`, in place of the model's own id.

Leave `--profile` out to use your default profile, and `--region` out when your AWS configuration already names a region.

## 3. Try one, and use it

```
codezaiku bedrock use us.anthropic.claude-sonnet-4-20250514-v1:0 --region us-east-1 --profile research
```

This sends one small request to the model, with a tool in it, because CodeZaiku does all its work through tools. If the model
answers, it becomes CodeZaiku's model and the choice is written into the settings. `codezaiku bedrock test <model>` does the
trying without changing anything, and `codezaiku doctor` checks the whole setup afterwards.

A reply from Bedrock is shown when it is complete, not word by word as it is written.

## When it says you are not allowed

The message tells you what AWS answered. Almost always it is one of two things, and both are settled in the AWS account, not
on your machine:

- **The model is not switched on.** In the AWS console, under Amazon Bedrock, "Model access" lists the models of each region
  and whether the account may use them. Anthropic's models ask for a short form to be filled in once per account.
- **Your group or role may not call it.** Give the policy in the next section to whoever manages the account.

"Your AWS sign-in is not accepted any more" means the sign-in has run out: `aws sso login` again.

## For the person who manages the AWS account

CodeZaiku calls Bedrock's Converse API, signed with the user's own credentials
(Signature Version 4). A group or permission set needs this much. Narrow the resources to the models you want to allow:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "UseTheModels",
      "Effect": "Allow",
      "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
      "Resource": [
        "arn:aws:bedrock:*::foundation-model/anthropic.claude-*",
        "arn:aws:bedrock:*:*:inference-profile/*"
      ]
    },
    {
      "Sid": "SeeWhatThereIs",
      "Effect": "Allow",
      "Action": ["bedrock:ListFoundationModels", "bedrock:ListInferenceProfiles"],
      "Resource": "*"
    }
  ]
}
```

In AWS GovCloud (US) the ARNs begin `arn:aws-us-gov:`, and in the China regions `arn:aws-cn:`. A call through an inference
profile needs the profile AND the foundation models behind it to be allowed, in every region the profile routes to.
`SeeWhatThereIs` is only for `codezaiku bedrock models`; without it everything else still works.

Requests go to `bedrock-runtime.<region>.amazonaws.com` and `bedrock.<region>.amazonaws.com` over HTTPS and nowhere else.
Usage is billed to the account like any other Bedrock use, and appears in CloudTrail under the user's identity.

## The settings it writes

| setting | meaning |
|---|---|
| `drive = bedrock` | use Amazon Bedrock. `bedrock:us-east-1` names the region in the same line |
| `bedrock.region` | the AWS region the models are in |
| `bedrock.profile` | the AWS profile to sign in with, when it is not your default |
| `model` | a Bedrock model id, or an inference profile's id or ARN |
| `bedrock.context` | how many tokens the model keeps in mind, when you know it. Bedrock cannot be asked, so CodeZaiku uses what is known of the model's family, and a cautious 32,000 for a model it does not know |

Bedrock replaces only the model. Everything CodeZaiku does on your machine, reading files and running commands, stays on your machine.
