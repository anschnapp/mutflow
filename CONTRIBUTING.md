# Contributing to Mutflow

Thanks for your interest in contributing to Mutflow! We appreciate your time and effort, and we want to make sure it's well spent. Please read the following guidelines before getting started.

## Start with a Discussion, Not a Pull Request

If you'd like to propose a new feature or a change in behavior, please open a discussion issue first before writing any code. This gives us a chance to talk through the idea together and avoids situations where you put in significant work on something that may not align with the project's direction.

Bug fixes and documentation improvements are of course always welcome as direct pull requests.

## About the Mutation Set

Mutflow is an opinionated project. One of its core goals is to find a pragmatic balance between thoroughness and usefulness — not every theoretically valid mutation belongs in the default set.

This means we may decline suggestions for new mutation operators even if they provide some value or are standard in other mutation testing tools. That's not a reflection on the quality of the suggestion; it's about keeping the tool focused and the results actionable.

If you're unsure whether a mutation operator would be a good fit, a discussion issue is the perfect place to explore that question.

## Getting Started

```bash
./gradlew build    # Build all modules
./gradlew test     # Run all tests
```

## Thank You

We genuinely value contributions and feedback. These guidelines exist to make the process smoother for everyone involved — not to discourage participation. If you have questions, don't hesitate to open an issue.
