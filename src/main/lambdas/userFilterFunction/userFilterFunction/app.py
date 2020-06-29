from __future__ import print_function

import json
import base64

print('Loading function')


def lambda_handler(event, context):
    output = []

    for record in event['records']:
        payload = base64.b64decode(record['data'])
        print("Payload ==> " + str(json.loads(payload.decode('utf-8'))))
        print("Record ID ==> " + str(record['recordId']))

        filter_array = json.loads(payload.decode('utf-8'))[0]
        print("Filter Array ==> " + str(filter_array))
        age = filter_array["dob"]["age"]

        if age > 20:
            output_record = {
                'recordId': record['recordId'],
                'result': 'Ok',
                # 'data': base64.b64encode(json.dumps(filter_array).encode('UTF-8')).decode('UTF-8')
                'data': base64.b64encode(payload).decode('utf-8')
            }
        else:
            output_record = {
                'recordId': record['recordId'],
                'result': 'Dropped',
                'data': base64.b64encode(payload).decode('utf-8')
            }

        output.append(output_record)

    output = {'records': output}
    print(output)
    return output