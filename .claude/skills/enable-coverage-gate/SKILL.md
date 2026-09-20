---
name: enable-coverage-gate
description: Turn JaCoCo's existing report into a build-failing coverage threshold, and make CI enforce it. Use when asked to enforce a minimum coverage percentage.
---

# Enable the coverage gate

JaCoCo is already wired in `backend/pom.xml`: `prepare-agent` plus a `report` execution bound to the `test` phase,
producing `target/site/jacoco/index.html`. What's missing is `check` — the goal that fails the build. That omission
is deliberate and commented as such in the pom; this skill is the intended way to reverse it.

## Before you pick a number

Run `mvn test` and read `target/site/jacoco/index.html`. **Set the threshold just under where coverage is today**,
then raise it. A threshold above current coverage means a red build on an unrelated commit and someone disabling the
gate within a week — which leaves you worse off than having no gate.

## Checklist

1. **Add a `check` execution** to the existing `jacoco-maven-plugin` block in `backend/pom.xml`, bound to `verify`:

   ```xml
   <execution>
     <id>jacoco-check</id>
     <phase>verify</phase>
     <goals><goal>check</goal></goals>
     <configuration>
       <rules>
         <rule>
           <element>BUNDLE</element>
           <limits>
             <limit>
               <counter>LINE</counter>
               <value>COVEREDRATIO</value>
               <minimum>0.00</minimum> <!-- set from the current report -->
             </limit>
           </limits>
         </rule>
       </rules>
     </configuration>
   </execution>
   ```

2. **Exclude what coverage can't say anything useful about** — records with generated accessors, `package-info`,
   config classes that are pure wiring, the `Application` class. Excluding a package because it's *hard* to test is
   how a gate becomes theatre; excluding one because a percentage there is meaningless is legitimate. Write the
   reason in a comment either way.

3. **Change the pipelines.** `check` is bound to `verify`, so `mvn test` will not run it. In **both**
   `harness/ci-build-pipeline.yaml` and `harness/build-and-deploy-k8s-pipeline.yaml`, in the
   `Backend tests + JaCoCo` step:

   ```diff
   - mvn -B test jacoco:report
   + mvn -B verify
   ```

   That one word is the entire gate as far as CI is concerned. Leaving it as `test` gives you a threshold that is
   enforced on developer machines and nowhere that matters.

4. **Leave the Dockerfile alone.** `docker build` runs `mvn package -DskipTests` and must keep doing so — the
   quick-start path has to work regardless of test state, and the image is deliberately not a quality gate. See
   `docs/SETUP.md`. Adding the gate to the image build would break `docker build` for anyone with a failing test and
   move the gate to the wrong place.

5. **Verify it actually gates**, in both directions:

   ```sh
   cd backend && mvn verify      # passes at the current threshold
   ```

   Then raise `<minimum>` to `0.99`, re-run, and confirm it fails with a JaCoCo rule violation. Restore. A gate never
   seen failing may not be a gate.

6. **Frontend, if asked** — Vitest coverage is a separate decision (`vitest --coverage` with `coverage.thresholds` in
   the Vite config, and `@vitest/coverage-v8` added). Don't do it silently as part of "turn on coverage"; the
   frontend's meaningful-coverage line is in a different place.

7. **Docs** — update `docs/SETUP.md` (running the tests), the coverage note in `docs/DEPLOYMENT.md` under CI/CD, and
   the pom comment that currently says no threshold exists. See `update-docs`.

## Verify

```sh
cd backend && mvn verify
```

Then let CI run it. A gate that passes locally and was never observed running in the pipeline isn't enforced yet.
