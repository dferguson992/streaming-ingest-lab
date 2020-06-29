import os
import json
import boto3
import requests


def lambda_handler(event, context):

    client = boto3.client('kinesis', region_name=str(os.environ['REGION']))

    r = requests.get('https://randomuser.me/api/?exc=login')
    data = r.json()
    response = client.put_record(
        StreamName=str(os.environ['KINESIS_STREAM_NAME']),
        Data=json.dumps(data["results"]),
        PartitionKey=str(data["info"]["seed"])
    )
    print(response)

