# ticket-streaming



### How to run

Assuming the runner doesnt have scala or sbt installed on their computer, this steps should be taken to run the API.

1. Install scala and sbt
   Please visit the following link to get scala and sbt installed.
   [Install scala & sbt]("https://docs.scala-lang.org/getting-started/index.html")

2. Clone the repository
   `git clone <repo_url>`


3. change directory into the cloned repo, pull dependencies and build
    ```shell script
     cd ticket-streaming
     sbt compile
    ```

4. After the success of step 3, we can then run the project with `sbt run` from normal powershell or unix shell or just `run` from the sbt shell


#### Test
To run the tests; do `sbt test` from the normal cli or `test` from the sbt prompt


#### API test

To test the api we can use postman or `curl` or `httpie` to call the /customer endpoint with the credentials of the customer. example using `httpie`
```shell
  printf '{
      "domain": "customer-domain",
      "token": "customer's OAuth token",
      "startTime": <time to start the stream in Epoch>
  }'| http  --follow --timeout 3600 POST 'localhost:8081/customers' \
   Content-Type:'application/json' 
```
